package com.airdrive.backup.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.RunOutcome
import com.airdrive.backup.data.db.RunTrigger
import com.airdrive.backup.data.prefs.NetworkPolicy
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.scanner.ScanProgress
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.DeviceState
import com.airdrive.backup.util.Format
import com.airdrive.backup.util.NotificationHelper
import com.airdrive.backup.util.StorageAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class BackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val repository = BackupRepository.get(appContext)
    private val settings = SettingsStore(appContext)
    private var lastNotifyAt = 0L
    private var lastNotifyText = ""

    /** Set only for a per-category "Upload" tap; null means every enabled category, as before. */
    private val categoryFilter: BackupCategory? =
        params.inputData.getString(WORK_INPUT_CATEGORY)?.let { runCatching { BackupCategory.valueOf(it) }.getOrNull() }

    /** Only the buttons set this; the periodic scheduler leaves it absent. */
    private val startedBy: RunTrigger =
        params.inputData.getString(WORK_INPUT_TRIGGER)
            ?.let { runCatching { RunTrigger.valueOf(it) }.getOrNull() }
            ?: RunTrigger.AUTOMATIC

    override suspend fun doWork(): Result {
        NotificationHelper.ensureChannel(applicationContext)
        // The previous run's result is stale the moment a new one starts, and so is any "waiting
        // for charging" notice: whatever it was waiting for has plainly arrived.
        NotificationHelper.clearResult(applicationContext)
        NotificationHelper.clearWaiting(applicationContext)
        notify("Starting backup…", 0, indeterminate = true)

        if (!StorageAccess.hasFullAccess(applicationContext)) {
            // Nothing to scan without storage access; the app shows how to grant it.
            Log.w(TAG, "no storage access - scanning authorized folders only")
        }

        repository.beginRun(startedBy, categoryFilter)

        return try {
            if (!repository.prepareTelegram()) {
                repository.finishRun(RunOutcome.BLOCKED, "Not signed in to Telegram")
                NotificationHelper.notifyResult(
                    context = applicationContext,
                    title = "Backup couldn't start",
                    summary = "Sign in to Telegram to continue",
                    route = Routes.TELEGRAM_LOGIN,
                    alert = true,
                    icon = android.R.drawable.stat_notify_error
                )
                Log.w(TAG, "Telegram session not ready, aborting run")
                return Result.failure()
            }

            notify("Scanning storage…", 0, indeterminate = true)
            val scan = repository.scan()

            repository.runBackupQueue(categoryFilter) { _, _ ->
                val p = repository.progress.value
                val label = if (p.totalFiles == 0) {
                    "Nothing to back up"
                } else {
                    "${p.doneFiles}/${p.totalFiles} • ${p.currentFileName ?: ""}".trim()
                }
                notify(label, p.percent, indeterminate = false)
            }

            repository.finishRun()
            postResult(scan)

            // Optional, off by default, and deliberately after the run is closed: an auto-delete
            // sweep must never be able to fail a backup that already succeeded.
            runCatching { repository.runAutoPurgeSweep() }
                .onSuccess { if (it > 0) Log.i(TAG, "auto-purge removed $it cloud-only file(s)") }
                .onFailure { Log.w(TAG, "auto-purge failed: ${it.message}") }

            val done = repository.progress.value
            Log.i(TAG, "run finished: ${done.doneFiles}/${done.totalFiles}, ${done.failedFiles} failed")
            Result.success()
        } catch (e: CancellationException) {
            // The run row has to be closed even though this coroutine is already cancelled, or the
            // timeline keeps a phantom "in progress" entry forever. NonCancellable also buys the
            // few milliseconds needed to work out *why* it stopped and say so.
            withContext(NonCancellable) {
                val parked = pausedReason()
                repository.finishRun(
                    RunOutcome.CANCELLED,
                    parked?.summary ?: "Stopped before finishing"
                )
                if (parked != null) {
                    val progress = repository.progress.value
                    NotificationHelper.notifyWaiting(
                        context = applicationContext,
                        title = "Backup paused",
                        summary = parked.summary,
                        lines = listOf(
                            parked.detail,
                            "${Format.count(progress.doneFiles)} of " +
                                "${Format.count(progress.totalFiles)} done so far — AirDrive picks " +
                                "up where it stopped."
                        ),
                        route = Routes.BACKUP_SETTINGS
                    )
                }
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "backup run crashed", e)
            val reason = e.message?.take(200) ?: e.javaClass.simpleName
            repository.finishRun(RunOutcome.FAILED, reason)
            NotificationHelper.notifyResult(
                context = applicationContext,
                title = "Backup stopped",
                summary = reason,
                lines = listOf("AirDrive will try again automatically."),
                route = Routes.DASHBOARD,
                alert = true,
                icon = android.R.drawable.stat_notify_error
            )
            Result.retry()
        }
    }

    /**
     * A run that was stopped from outside, explained. Only the periodic run carries the charging and
     * battery constraints, so a cable pulled during a manual one is never the answer and this says
     * nothing rather than inventing a reason; the network policy applies to every run, manual
     * included. Returning null is the honest outcome when the phone looks perfectly capable of
     * backing up — which is what the Stop button looks like from in here.
     */
    private suspend fun pausedReason(): Parked? {
        val context = applicationContext

        if (startedBy == RunTrigger.AUTOMATIC) {
            if (settings.chargingOnly.first() && !DeviceState.isCharging(context)) {
                return Parked(
                    summary = "Waiting for charging",
                    detail = "Automatic backups are set to run only while charging. Plug the " +
                        "phone in and this carries on by itself."
                )
            }
            if (settings.batteryConscious.first() && DeviceState.isBatteryLow(context)) {
                val percent = DeviceState.batteryPercent(context)
                return Parked(
                    summary = if (percent >= 0) "Waiting for battery ($percent%)" else "Waiting for battery",
                    detail = "AirDrive stops uploading on a low battery so it does not flatten " +
                        "the phone. It resumes once there is more charge."
                )
            }
        }

        return when (settings.networkPolicy.first()) {
            NetworkPolicy.WIFI_ONLY -> if (!DeviceState.isUnmetered(context)) {
                Parked(
                    summary = "Waiting for Wi-Fi",
                    detail = "Backups are set to Wi-Fi only. Nothing will be uploaded over " +
                        "mobile data — change this in Backup settings if you would rather it did."
                )
            } else {
                null
            }
            NetworkPolicy.NOT_ROAMING, NetworkPolicy.ANY -> if (!DeviceState.hasNetwork(context)) {
                Parked(
                    summary = "Waiting for a connection",
                    detail = "The phone went offline part-way through. AirDrive resumes on its own."
                )
            } else {
                null
            }
        }
    }

    /** One reason a run is parked rather than finished: the headline, and the sentence under it. */
    private data class Parked(val summary: String, val detail: String)

    /**
     * The end-of-run notification, with the numbers that actually matter. "Backup finished" told
     * the user nothing; "143 files • 2.7 GB • 0 failures" tells them whether to care, and a run
     * with failures says what to tap.
     */
    private fun postResult(scan: ScanProgress) {
        val p = repository.progress.value
        val uploaded = (p.doneFiles - p.failedFiles).coerceAtLeast(0)
        val failed = p.failedFiles

        // Silence is the right answer for a routine automatic run that found nothing to do.
        if (uploaded == 0 && failed == 0 && startedBy == RunTrigger.AUTOMATIC) {
            Log.i(TAG, "nothing to do; skipping result notification")
            return
        }

        val detail = buildList {
            if (scan.filesScanned > 0) {
                add(
                    "${Format.count(scan.filesScanned)} scanned • " +
                        "${Format.count(scan.filesUnchanged)} already backed up"
                )
            }
            if (scan.filesModified > 0) add("${Format.count(scan.filesModified)} changed since last time")
            if (scan.filesRenamed > 0) add("${Format.count(scan.filesRenamed)} moved or renamed — not re-uploaded")
            if (scan.filesMissing > 0) add("${Format.count(scan.filesMissing)} deleted from this phone — copies kept")
        }

        when {
            failed > 0 -> NotificationHelper.notifyResult(
                context = applicationContext,
                title = "Backup incomplete",
                summary = "$failed file(s) failed • tap to review",
                lines = listOf("${Format.count(uploaded)} uploaded • ${Format.bytes(p.bytesUploaded)}") + detail,
                route = Routes.FAILED_UPLOADS,
                alert = true,
                icon = android.R.drawable.stat_notify_error
            )
            uploaded > 0 -> NotificationHelper.notifyResult(
                context = applicationContext,
                title = "Backup completed",
                summary = "${Format.count(uploaded)} file(s) • ${Format.bytes(p.bytesUploaded)} • no failures",
                lines = detail,
                route = Routes.TIMELINE
            )
            else -> NotificationHelper.notifyResult(
                context = applicationContext,
                title = "Everything is already backed up",
                summary = if (scan.filesScanned > 0) {
                    "${Format.count(scan.filesScanned)} file(s) checked, nothing new to upload"
                } else {
                    "Nothing to back up yet"
                },
                lines = detail,
                route = Routes.TIMELINE
            )
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
