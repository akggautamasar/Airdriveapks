package com.airdrive.backup.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.data.repo.MigrationState
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.Format
import com.airdrive.backup.util.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pulls whole categories back down from Telegram onto a new phone (wishlist item 8).
 *
 * This is a worker rather than a coroutine in the repository for one reason: duration. Restoring a
 * filled-up phone is tens of gigabytes and hours of transfer, which means the user will leave the
 * screen, leave the app, and quite possibly have the process killed while it runs. WorkManager keeps
 * it alive across all three and gives it the foreground notification Android now demands for that
 * kind of work.
 *
 * The work itself lives in [BackupRepository.migrateNow]; this class only supplies the lifetime, the
 * notification, and the end-of-run summary. Progress is published through the repository's shared
 * `migration` flow, so the screen shows the same run whether or not it was the thing that started it.
 */
class MigrationWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val repository = BackupRepository.get(appContext)
    private var lastNotifyAt = 0L
    private var lastNotifyText = ""

    /** Unknown names are dropped rather than fatal: an input built by an older version still runs. */
    private val categories: Set<BackupCategory> =
        params.inputData.getString(WORK_INPUT_MIGRATION_CATEGORIES)
            ?.split(',')
            ?.mapNotNull { name -> runCatching { BackupCategory.valueOf(name.trim()) }.getOrNull() }
            ?.toSet()
            ?: emptySet()

    /** Defaults to true, the resumable behaviour — the destructive-ish option must be asked for. */
    private val skipRestored: Boolean =
        params.inputData.getBoolean(WORK_INPUT_MIGRATION_SKIP, true)

    override suspend fun doWork(): Result {
        if (categories.isEmpty()) {
            Log.w(TAG, "migration requested with no categories")
            return Result.failure()
        }

        NotificationHelper.ensureChannel(applicationContext)
        notify("Preparing to restore…", 0, indeterminate = true)

        return try {
            // The repository owns the loop; this side just keeps the notification honest while it
            // runs. Polling at the same rate the notification is allowed to change is simpler than
            // collecting every state emission and throwing almost all of them away.
            coroutineScope {
                val watcher = launch {
                    while (isActive) {
                        publish(repository.migration.value)
                        delay(1000)
                    }
                }
                try {
                    repository.migrateNow(categories, skipRestored)
                } finally {
                    watcher.cancel()
                }
            }

            val state = repository.migration.value
            postResult(state)
            Log.i(TAG, "migration worker done: ${state.filesDone} restored, ${state.filesFailed} failed")
            // A failed queue is not a failed worker: retrying the whole thing on a schedule would
            // hammer files that are genuinely gone. The user re-taps, which is resumable and cheap.
            if (state.error != null) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            // Either the user cancelled or a constraint dropped out. The repository has already
            // published the cancelled state, and every file that landed stays restored.
            Log.i(TAG, "migration stopped early")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "migration worker crashed", e)
            NotificationHelper.notifyResult(
                context = applicationContext,
                title = "Restore stopped",
                summary = e.message?.take(200) ?: e.javaClass.simpleName,
                lines = listOf("Files already restored are safe. Tap to try again."),
                route = Routes.MIGRATE,
                alert = true,
                icon = android.R.drawable.stat_notify_error
            )
            Result.retry()
        }
    }

    /** One line the user can act on, in the same shape as the end-of-backup notification. */
    private fun postResult(state: MigrationState) {
        val restored = state.filesDone
        val failed = state.filesFailed
        val detail = buildList {
            if (restored > 0) add("Saved to Downloads/AirDrive, one folder per category")
            if (failed > 0) add("The files that failed are still in Telegram")
        }

        when {
            state.error != null -> NotificationHelper.notifyResult(
                context = applicationContext,
                title = "Restore stopped",
                summary = state.error,
                lines = listOf("${Format.count(restored)} file(s) restored before it stopped"),
                route = Routes.MIGRATE,
                alert = true,
                icon = android.R.drawable.stat_notify_error
            )
            state.cancelled -> NotificationHelper.notifyResult(
                context = applicationContext,
                title = "Restore paused",
                summary = "${Format.count(restored)} file(s) restored • tap to carry on",
                lines = listOf("It picks up from the first file that did not arrive"),
                route = Routes.MIGRATE
            )
            failed > 0 -> NotificationHelper.notifyResult(
                context = applicationContext,
                title = "Restore incomplete",
                summary = "$failed file(s) could not be restored • tap to review",
                lines = listOf(
                    "${Format.count(restored)} restored • ${Format.bytes(state.bytesDone)}"
                ) + detail,
                route = Routes.MIGRATE,
                alert = true,
                icon = android.R.drawable.stat_notify_error
            )
            restored > 0 -> NotificationHelper.notifyResult(
                context = applicationContext,
                title = "Restore completed",
                summary = "${Format.count(restored)} file(s) • ${Format.bytes(state.bytesDone)}",
                lines = detail,
                route = Routes.MIGRATE
            )
            else -> NotificationHelper.notifyResult(
                context = applicationContext,
                title = "Nothing left to restore",
                summary = "Everything in the backup index is already on this phone",
                route = Routes.MIGRATE
            )
        }
    }

    /** Turns migration state into the ongoing notification's title line. */
    private suspend fun publish(state: MigrationState) {
        if (!state.running) return
        val text = when {
            state.filesTotal == 0 -> "Reading the backup list…"
            else -> "${Format.count(state.filesDone)}/${Format.count(state.filesTotal)} • " +
                (state.currentFile ?: "")
        }
        notify(text.trim(), (state.fraction * 100).toInt(), indeterminate = state.filesTotal == 0)
    }

    /**
     * Same throttle as the backup worker: setForeground() posts a notification, so doing it per file
     * would be thousands of posts and the system would start dropping them.
     */
    private suspend fun notify(text: String, percent: Int, indeterminate: Boolean) {
        val now = System.currentTimeMillis()
        if (text == lastNotifyText && now - lastNotifyAt < 1000L) return
        lastNotifyText = text
        lastNotifyAt = now
        val notification = NotificationHelper.buildProgressNotification(
            applicationContext,
            "Restoring from Telegram",
            text,
            percent.coerceIn(0, 100),
            indeterminate,
            icon = android.R.drawable.stat_sys_download,
            route = Routes.MIGRATE
        )
        try {
            setForeground(foregroundInfo(notification))
        } catch (e: Exception) {
            // Android 12+ refuses a foreground service in some app states; the download is fine.
            Log.w(TAG, "setForeground rejected: ${e.message}")
        }
    }

    /**
     * Its own notification id, not the backup's: a scheduled backup can be running at the same time
     * as a migration, and two foreground services sharing one id overwrite each other.
     */
    private fun foregroundInfo(notification: android.app.Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NotificationHelper.MIGRATION_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NotificationHelper.MIGRATION_NOTIFICATION_ID, notification)
        }

    private companion object {
        const val TAG = "AirDrive.Migration"
    }
}
