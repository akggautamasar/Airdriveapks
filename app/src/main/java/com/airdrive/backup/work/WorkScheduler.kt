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
import com.airdrive.backup.data.prefs.NetworkPolicy
import com.airdrive.backup.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

object WorkScheduler {
    private const val MANUAL_WORK_NAME = "airdrive_manual_backup"
    private const val AUTO_WORK_NAME = "airdrive_auto_backup"

    /** Process-lifetime scope: reading settings suspends, but onClick handlers cannot. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Fire-and-forget version for buttons. */
    fun runNow(context: Context) {
        scope.launch { runNowAwait(context) }
    }

    /**
     * Kicks off a manual run under the user's own network policy. The previous version always
     * used NetworkType.CONNECTED, so "Wi-Fi only" was quietly ignored by "Back up now" and people
     * burned mobile data. Charging is deliberately *not* required here: the user asked for it now.
     */
    suspend fun runNowAwait(context: Context) {
        val policy = SettingsStore(context).networkPolicy.first()
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkTypeFor(policy)).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        // REPLACE, not KEEP: with KEEP a finished-but-still-registered run made "Back up now"
        // silently do nothing, which looked exactly like the app being stuck.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun networkTypeFor(policy: NetworkPolicy): NetworkType = when (policy) {
        NetworkPolicy.WIFI_ONLY -> NetworkType.UNMETERED
        NetworkPolicy.NOT_ROAMING -> NetworkType.NOT_ROAMING
        NetworkPolicy.ANY -> NetworkType.CONNECTED
    }

    /** Live state of the manual run, so the UI can show whether anything is actually queued. */
    fun manualWorkInfo(context: Context) =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(MANUAL_WORK_NAME)

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

        val chargingOnly = settings.chargingOnly.first()
        val batteryConscious = settings.batteryConscious.first()
        val frequencyHours = settings.backupFrequencyHours.first().coerceAtLeast(1)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkTypeFor(settings.networkPolicy.first()))
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
