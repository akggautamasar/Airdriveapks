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
import com.airdrive.backup.util.NotificationHelper
import com.airdrive.backup.util.StorageAccess
import kotlinx.coroutines.CancellationException

class BackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val repository = BackupRepository.get(appContext)
    private var lastNotifyAt = 0L
    private var lastNotifyText = ""

    /** Set only for a per-category "Upload" tap; null means every enabled category, as before. */
    private val categoryFilter: BackupCategory? =
        params.inputData.getString(WORK_INPUT_CATEGORY)?.let { runCatching { BackupCategory.valueOf(it) }.getOrNull() }

    override suspend fun doWork(): Result {
        NotificationHelper.ensureChannel(applicationContext)
        notify("Starting backup…", 0, indeterminate = true)

        if (!StorageAccess.hasFullAccess(applicationContext)) {
            // Nothing to scan without storage access; the app shows how to grant it.
            Log.w(TAG, "no storage access - scanning authorized folders only")
        }

        return try {
            if (!repository.prepareTelegram()) {
                notify("Sign in to Telegram to continue", 0, indeterminate = false)
                Log.w(TAG, "Telegram session not ready, aborting run")
                return Result.failure()
            }

            notify("Scanning storage…", 0, indeterminate = true)
            repository.scan()

            repository.runBackupQueue(categoryFilter) { _, _ ->
                val p = repository.progress.value
                val label = if (p.totalFiles == 0) {
                    "Nothing to back up"
                } else {
                    "${p.doneFiles}/${p.totalFiles} • ${p.currentFileName ?: ""}".trim()
                }
                notify(label, p.percent, indeterminate = false)
            }

            val done = repository.progress.value
            Log.i(TAG, "run finished: ${done.doneFiles}/${done.totalFiles}, ${done.failedFiles} failed")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "backup run crashed", e)
            Result.retry()
        }
    }

    /**
     * setForeground() posts a notification, so calling it for every progress tick (thousands of
     * times) both wastes battery and gets throttled by the system. Once per second, or whenever
     * the text actually changes materially, is plenty.
     */
    private suspend fun notify(text: String, percent: Int, indeterminate: Boolean) {
        val now = System.currentTimeMillis()
        if (text == lastNotifyText && now - lastNotifyAt < 1000L) return
        lastNotifyText = text
        lastNotifyAt = now
        val notification = NotificationHelper.buildProgressNotification(
            applicationContext,
            "AirDrive Backup",
            text,
            percent.coerceIn(0, 100),
            indeterminate
        )
        try {
            setForeground(foregroundInfo(notification))
        } catch (e: Exception) {
            // Android 12+ throws if the app is in a state where a foreground service cannot be
            // started; the upload itself is unaffected, so keep going.
            Log.w(TAG, "setForeground rejected: ${e.message}")
        }
    }

    /** Android 14 refuses a foreground service with no declared type. */
    private fun foregroundInfo(notification: android.app.Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NotificationHelper.BACKUP_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NotificationHelper.BACKUP_NOTIFICATION_ID, notification)
        }

    private companion object {
        const val TAG = "AirDrive.Worker"
    }
}
