package com.airdrive.backup.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.airdrive.backup.data.backup.TelegramChannelSync
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.NotificationHelper

/** Long-running Telegram inventory import. It survives leaving the screen and process recreation. */
class TelegramChannelSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val sync = TelegramChannelSync.get(appContext)
    private var lastNotifyAt = 0L
    private var lastNotifyText = ""

    override suspend fun doWork(): Result {
        NotificationHelper.ensureChannel(applicationContext)
        notify("Preparing Telegram inventory…", 0, true)
        return try {
            val result = sync.syncConfiguredChannels { text ->
                // The scanner calls this for every Telegram history page. Keep the foreground
                // notification current without hammering Android with thousands of updates.
                val now = System.currentTimeMillis()
                if (text != lastNotifyText || now - lastNotifyAt >= 1000L) {
                    lastNotifyText = text
                    lastNotifyAt = now
                    val scanned = Regex("(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    val percent = (scanned % 10000) / 100
                    setProgress(Data.Builder().putInt("messages_scanned", scanned).putString("status", text).build())
                    notify(text, percent, false)
                }
            }
            val uploadedInManifest = result.manifestEntries
            val output = Data.Builder()
                .putInt("channels", result.channels)
                .putInt("messages_scanned", result.messagesScanned)
                .putInt("files_found", result.messagesScanned)
                .putInt("files_imported", result.filesImported)
                .putInt("already_indexed", result.alreadyIndexed)
                .putInt("failed_channels", result.failedChannels)
                .putInt("manifest_entries", uploadedInManifest)
                .build()
            setProgress(output)
            NotificationHelper.notifyResult(
                applicationContext,
                if (result.failedChannels == 0) "Telegram inventory complete" else "Telegram inventory partially complete",
                "${result.filesImported} added • ${result.alreadyIndexed} already indexed • ${result.messagesScanned} files found",
                listOf(
                    "${result.channels} channel(s) scanned • ${result.manifestEntries} files in manifest",
                    if (result.failedChannels > 0) "${result.failedChannels} channel(s) could not be scanned; run again to retry." else "All connected channels completed."
                ),
                Routes.CATEGORIES_STATS,
                result.failedChannels > 0
            )
            Result.success(output)
        } catch (e: Exception) {
            Log.e("AirDrive.TelegramSync", "inventory worker failed", e)
            NotificationHelper.notifyResult(
                applicationContext,
                "Telegram inventory stopped",
                e.message?.take(180) ?: "The inventory job was interrupted",
                listOf("Your indexed files remain safe. Start import again to resume."),
                Routes.CATEGORIES_STATS,
                true,
                android.R.drawable.stat_notify_error
            )
            Result.retry()
        }
    }

    private suspend fun notify(text: String, percent: Int, indeterminate: Boolean) {
        val notification = NotificationHelper.buildProgressNotification(
            applicationContext,
            "AirDrive Telegram inventory",
            text,
            percent.coerceIn(0, 100),
            indeterminate,
            android.R.drawable.stat_sys_download,
            Routes.CATEGORIES_STATS
        )
        try { setForeground(foregroundInfo(notification)) }
        catch (e: Exception) { Log.w("AirDrive.TelegramSync", "foreground notification unavailable: ${e.message}") }
    }

    private fun foregroundInfo(notification: android.app.Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationHelper.MIGRATION_NOTIFICATION_ID + 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NotificationHelper.MIGRATION_NOTIFICATION_ID + 1, notification)
        }
}
