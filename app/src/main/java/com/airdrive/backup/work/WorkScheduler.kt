package com.airdrive.backup.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.airdrive.backup.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

object WorkScheduler {
    private const val MANUAL_WORK_NAME = "airdrive_manual_backup"
    private const val AUTO_WORK_NAME = "airdrive_auto_backup"

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun pauseManual(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(MANUAL_WORK_NAME)
    }

    suspend fun rescheduleAutoBackup(context: Context) {
        val settings = SettingsStore(context)
        val manager = WorkManager.getInstance(context)

        if (!settings.autoBackupEnabled.first()) {
            manager.cancelUniqueWork(AUTO_WORK_NAME)
            return
        }

        val wifiOnly = settings.wifiOnly.first()
        val allowMobile = settings.allowMobileData.first()
        val chargingOnly = settings.chargingOnly.first()
        val batteryConscious = settings.batteryConscious.first()
        val frequencyHours = settings.backupFrequencyHours.first().coerceAtLeast(1)

        val networkType = when {
            wifiOnly && !allowMobile -> NetworkType.UNMETERED
            else -> NetworkType.CONNECTED
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(chargingOnly)
            .setRequiresBatteryNotLow(batteryConscious)
            .build()

        val request = PeriodicWorkRequestBuilder<BackupWorker>(frequencyHours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()

        manager.enqueueUniquePeriodicWork(AUTO_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
