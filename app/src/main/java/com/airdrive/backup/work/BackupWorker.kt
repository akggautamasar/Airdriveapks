package com.airdrive.backup.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.util.NotificationHelper

class BackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val repository = BackupRepository(appContext)

    override suspend fun doWork(): Result {
        NotificationHelper.ensureChannel(applicationContext)
        setForeground(foregroundInfo(0, "Starting backup\u2026", indeterminate = true))

        return try {
            repository.scan()
            setForeground(foregroundInfo(0, "Uploading files\u2026", indeterminate = false))

            repository.runBackupQueue { record, success ->
                val p = repository.progress.value
                val percent = if (p.totalFiles == 0) 0 else (p.doneFiles * 100 / p.totalFiles)
                setForeground(
                    foregroundInfo(
                        percent,
                        "${p.doneFiles}/${p.totalFiles} \u2022 ${record.displayName}",
                        indeterminate = false
                    )
                )
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun foregroundInfo(percent: Int, text: String, indeterminate: Boolean): ForegroundInfo {
        val notification = NotificationHelper.buildProgressNotification(
            applicationContext, "AirDrive Backup", text, percent, indeterminate
        )
        return ForegroundInfo(NotificationHelper.BACKUP_NOTIFICATION_ID, notification)
    }
}
