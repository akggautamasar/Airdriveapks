package com.airdrive.backup.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.prefs.NetworkPolicy
import com.airdrive.backup.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

const val WORK_INPUT_CATEGORY = "category_filter"
const val WORK_INPUT_TRIGGER = "run_trigger"
const val WORK_INPUT_MIGRATION_CATEGORIES = "migration_categories"
const val WORK_INPUT_MIGRATION_SKIP = "migration_skip_restored"

object WorkScheduler {
    private const val MANUAL_WORK_NAME = "airdrive_manual_backup"
    private const val AUTO_WORK_NAME = "airdrive_auto_backup"
    private const val MIGRATION_WORK_NAME = "airdrive_migration"
    private const val TELEGRAM_IMPORT_WORK_NAME = "airdrive_telegram_import"
    private const val TELEGRAM_AUTO_SYNC_WORK_NAME = "airdrive_telegram_auto_sync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun runNow(context: Context) { scope.launch { runNowAwait(context) } }
    fun runNowCategory(context: Context, category: BackupCategory) { scope.launch { runNowAwait(context, category) } }

    suspend fun runNowAwait(context: Context, category: BackupCategory? = null) {
        val policy = SettingsStore(context).networkPolicy.first()
        val input = Data.Builder().putString(WORK_INPUT_TRIGGER, if (category == null) "MANUAL" else "CATEGORY")
        if (category != null) input.putString(WORK_INPUT_CATEGORY, category.name)
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkTypeFor(policy)).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setInputData(input.build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** One click starts a durable foreground WorkManager job. KEEP prevents a second tap from restarting it. */
    fun importTelegramChannels(context: Context) {
        scope.launch { importTelegramChannelsAwait(context) }
    }

    suspend fun importTelegramChannelsAwait(context: Context) {
        val policy = SettingsStore(context).networkPolicy.first()
        val request = OneTimeWorkRequestBuilder<TelegramChannelSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkTypeFor(policy)).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            TELEGRAM_IMPORT_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /** Keeps connected channels reconciled automatically after the initial full import. */
    fun rescheduleTelegramAutoSync(context: Context) {
        scope.launch {
            val policy = SettingsStore(context).networkPolicy.first()
            val request = PeriodicWorkRequestBuilder<TelegramChannelSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkTypeFor(policy)).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                TELEGRAM_AUTO_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }

    fun telegramImportWorkInfo(context: Context) =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(TELEGRAM_IMPORT_WORK_NAME)

    private fun networkTypeFor(policy: NetworkPolicy): NetworkType = when (policy) {
        NetworkPolicy.WIFI_ONLY -> NetworkType.UNMETERED
        NetworkPolicy.NOT_ROAMING -> NetworkType.NOT_ROAMING
        NetworkPolicy.ANY -> NetworkType.CONNECTED
    }

    fun startMigration(context: Context, categories: Set<BackupCategory>, skipRestored: Boolean) {
        if (categories.isEmpty()) return
        scope.launch { startMigrationAwait(context, categories, skipRestored) }
    }
    suspend fun startMigrationAwait(context: Context, categories: Set<BackupCategory>, skipRestored: Boolean) {
        if (categories.isEmpty()) return
        val policy = SettingsStore(context).networkPolicy.first()
        val input = Data.Builder()
            .putString(WORK_INPUT_MIGRATION_CATEGORIES, categories.joinToString(",") { it.name })
            .putBoolean(WORK_INPUT_MIGRATION_SKIP, skipRestored).build()
        val request = OneTimeWorkRequestBuilder<MigrationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkTypeFor(policy)).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setInputData(input).build()
        WorkManager.getInstance(context).enqueueUniqueWork(MIGRATION_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
    fun cancelMigration(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(MIGRATION_WORK_NAME) }
    fun manualWorkInfo(context: Context) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(MANUAL_WORK_NAME)
    fun pauseManual(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(MANUAL_WORK_NAME) }

    suspend fun rescheduleAutoBackup(context: Context) {
        val settings = SettingsStore(context); val manager = WorkManager.getInstance(context)
        if (!settings.autoBackupEnabled.first()) { manager.cancelUniqueWork(AUTO_WORK_NAME); return }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkTypeFor(settings.networkPolicy.first()))
            .setRequiresCharging(settings.chargingOnly.first())
            .setRequiresBatteryNotLow(settings.batteryConscious.first()).build()
        val request = PeriodicWorkRequestBuilder<BackupWorker>(settings.backupFrequencyHours.first().coerceAtLeast(1), TimeUnit.HOURS)
            .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork(AUTO_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
