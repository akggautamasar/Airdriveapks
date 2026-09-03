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

/** Key BackupWorker reads to restrict a manual run to a single category; absent = every category. */
const val WORK_INPUT_CATEGORY = "category_filter"

/**
 * Key BackupWorker reads to label the run on the timeline. Absent means the periodic scheduler
 * started it, since only the buttons below set it explicitly.
 */
const val WORK_INPUT_TRIGGER = "run_trigger"

/** Keys MigrationWorker reads: which categories to pull down, comma-joined enum names. */
const val WORK_INPUT_MIGRATION_CATEGORIES = "migration_categories"

/** Key MigrationWorker reads to decide whether files already pulled down are fetched again. */
const val WORK_INPUT_MIGRATION_SKIP = "migration_skip_restored"

object WorkScheduler {
    private const val MANUAL_WORK_NAME = "airdrive_manual_backup"
    private const val AUTO_WORK_NAME = "airdrive_auto_backup"
    private const val MIGRATION_WORK_NAME = "airdrive_migration"

    /** Process-lifetime scope: reading settings suspends, but onClick handlers cannot. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Fire-and-forget version for buttons. */
    fun runNow(context: Context) {
        scope.launch { runNowAwait(context) }
    }

    /** Same as [runNow] but restricted to one category — the per-category "Upload" buttons. */
    fun runNowCategory(context: Context, category: BackupCategory) {
        scope.launch { runNowAwait(context, category) }
    }

    /**
     * Kicks off a manual run under the user's own network policy. The previous version always
     * used NetworkType.CONNECTED, so "Wi-Fi only" was quietly ignored by "Back up now" and people
     * burned mobile data. Charging is deliberately *not* required here: the user asked for it now.
     */
    suspend fun runNowAwait(context: Context, category: BackupCategory? = null) {
        val policy = SettingsStore(context).networkPolicy.first()
        val input = Data.Builder()
            .putString(WORK_INPUT_TRIGGER, if (category == null) "MANUAL" else "CATEGORY")
        if (category != null) input.putString(WORK_INPUT_CATEGORY, category.name)
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkTypeFor(policy)).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setInputData(input.build())
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

    // ---- device-to-device migration

    /** Fire-and-forget for the "Start restore" button, which cannot suspend. */
    fun startMigration(context: Context, categories: Set<BackupCategory>, skipRestored: Boolean) {
        if (categories.isEmpty()) return
        scope.launch { startMigrationAwait(context, categories, skipRestored) }
    }

    /**
     * Restoring a phone takes hours, so it belongs in a worker rather than in a coroutine that dies
     * with the app: WorkManager keeps it alive across process death and gives it a foreground
     * notification. Network policy is respected — a 40 GB restore over mobile data would be a
     * genuinely expensive surprise — but charging is not required, because the user asked for this
     * now and a migration that silently waits for a cable looks broken.
     */
    suspend fun startMigrationAwait(
        context: Context,
        categories: Set<BackupCategory>,
        skipRestored: Boolean
    ) {
        if (categories.isEmpty()) return
        val policy = SettingsStore(context).networkPolicy.first()
        val input = Data.Builder()
            .putString(WORK_INPUT_MIGRATION_CATEGORIES, categories.joinToString(",") { it.name })
            .putBoolean(WORK_INPUT_MIGRATION_SKIP, skipRestored)
            .build()
        val request = OneTimeWorkRequestBuilder<MigrationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkTypeFor(policy)).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .setInputData(input)
            .build()
        // REPLACE for the same reason as a manual backup: a stale finished entry must never make
        // "Start restore" do nothing. Re-enqueueing is harmless, since a migration is resumable.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(MIGRATION_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Stops the migration after the file currently in flight. Restored files stay restored. */
    fun cancelMigration(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(MIGRATION_WORK_NAME)
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
