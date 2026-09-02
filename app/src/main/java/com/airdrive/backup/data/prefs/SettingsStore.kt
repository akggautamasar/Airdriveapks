package com.airdrive.backup.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.airdrive.backup.BuildConfig
import com.airdrive.backup.data.db.BackupCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "airdrive_settings")

data class ChannelMap(val perCategory: Map<BackupCategory, Long>)

/**
 * Where uploads go. SAVED_MESSAGES needs no setup at all (it is the signed-in account's own
 * chat), SINGLE_CHAT puts everything in one channel, PER_CATEGORY is the original behaviour of
 * one channel per file type.
 */
enum class DestinationMode { SAVED_MESSAGES, SINGLE_CHAT, PER_CATEGORY }

/** One setting instead of the old wifiOnly/allowMobileData pair, which could contradict itself. */
enum class NetworkPolicy { WIFI_ONLY, NOT_ROAMING, ANY }

/** Which end of the queue to drain first. Smallest-first clears the file count fastest. */
enum class UploadOrder { OLDEST_FIRST, NEWEST_FIRST, SMALLEST_FIRST }

data class ApiCredentials(val apiId: Int, val apiHash: String, val fromUser: Boolean) {
    val isUsable: Boolean get() = apiId != 0 && apiHash.isNotBlank()
}

data class DestinationConfig(
    val mode: DestinationMode,
    val singleChatId: Long,
    val perCategory: Map<BackupCategory, Long>
) {
    /** True when the user still has to tell AirDrive where to put things. */
    val needsSetup: Boolean
        get() = when (mode) {
            DestinationMode.SAVED_MESSAGES -> false
            DestinationMode.SINGLE_CHAT -> singleChatId == 0L
            DestinationMode.PER_CATEGORY -> perCategory.values.all { it == 0L }
        }
}

object Keys {
    fun channelKey(cat: BackupCategory) = longPreferencesKey("channel_${cat.name}")
    val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
    val WIFI_ONLY = booleanPreferencesKey("wifi_only")
    val CHARGING_ONLY = booleanPreferencesKey("charging_only")
    val ALLOW_MOBILE_DATA = booleanPreferencesKey("allow_mobile_data")
    val BATTERY_CONSCIOUS = booleanPreferencesKey("battery_conscious")
    val INCLUDE_SMALL_FILES = booleanPreferencesKey("include_small_files")
    val BACKUP_FREQUENCY_HOURS = longPreferencesKey("backup_frequency_hours")
    val ENABLED_CATEGORIES = stringSetPreferencesKey("enabled_categories")
    val AUTHORIZED_TREE_URIS = stringSetPreferencesKey("authorized_tree_uris")
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    val TELEGRAM_LOGGED_IN = booleanPreferencesKey("telegram_logged_in")

    /** Scan every folder under internal storage instead of user-picked SAF trees. */
    val SCAN_WHOLE_DEVICE = booleanPreferencesKey("scan_whole_device")
    val INCLUDE_SD_CARD = booleanPreferencesKey("include_sd_card")

    /**
     * Set once after whole-device mode takes over, when the leftover SAF-queued rows from the
     * folder-picking era are dropped so the same file is not queued twice under two URIs.
     */
    val SAF_QUEUE_PURGED = booleanPreferencesKey("saf_queue_purged")

    /** Telegram app credentials the user entered themselves; absent = use the build's own. */
    val API_ID = intPreferencesKey("telegram_api_id")
    val API_HASH = stringPreferencesKey("telegram_api_hash")

    val DESTINATION_MODE = stringPreferencesKey("destination_mode")
    val SINGLE_CHAT_ID = longPreferencesKey("single_chat_id")
    val NETWORK_POLICY = stringPreferencesKey("network_policy")
    val UPLOAD_ORDER = stringPreferencesKey("upload_order")
    val EXCLUDED_PATHS = stringSetPreferencesKey("excluded_paths")
    val MAX_FILE_SIZE_MB = longPreferencesKey("max_file_size_mb")
    val CAPTION_TEMPLATE = stringPreferencesKey("caption_template")
    val AUTO_RETRY_FAILED = booleanPreferencesKey("auto_retry_failed")

    /**
     * 1 = upgraded from a build that shipped hardcoded channel IDs, 2 = installed fresh after
     * multiple destinations existed. Pinned on first launch so a fresh install never inherits
     * somebody else's channels, and an upgrade never loses its own.
     */
    val INSTALL_GENERATION = intPreferencesKey("install_generation")
}

/**
 * The channel IDs the first AirDrive build shipped as defaults. They belong to whoever built
 * that APK, so they are only ever applied to installs that were already using them
 * (generation 1); a fresh install starts with no destination at all and is asked to pick one.
 */
object LegacyChannels {
    val map: Map<BackupCategory, Long> = mapOf(
        BackupCategory.CALL_RECORDINGS to -1004274179262L,
        BackupCategory.WORD_EXCEL to -1003999074582L,
        BackupCategory.OTHER_FILES to -1004237723796L,
        BackupCategory.PHOTOS to -1004291403787L,
        BackupCategory.VIDEOS to -1003982372929L,
        BackupCategory.PDFS to -1003416055978L,
        BackupCategory.AUDIO to -1003935949819L
    )
}

const val DEFAULT_CAPTION_TEMPLATE = "📄 {name}\n📅 {date}\n💾 {size}\n📁 {folder}"

class SettingsStore(private val context: Context) {

    val autoBackupEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AUTO_BACKUP_ENABLED] ?: true }

    val chargingOnly: Flow<Boolean> = context.dataStore.data.map { it[Keys.CHARGING_ONLY] ?: false }
    val batteryConscious: Flow<Boolean> = context.dataStore.data.map { it[Keys.BATTERY_CONSCIOUS] ?: true }
    val includeSmallFiles: Flow<Boolean> = context.dataStore.data.map { it[Keys.INCLUDE_SMALL_FILES] ?: false }
    val backupFrequencyHours: Flow<Long> = context.dataStore.data.map { it[Keys.BACKUP_FREQUENCY_HOURS] ?: 6L }
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }
    val telegramLoggedIn: Flow<Boolean> = context.dataStore.data.map { it[Keys.TELEGRAM_LOGGED_IN] ?: false }
    val autoRetryFailed: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_RETRY_FAILED] ?: true }

    val enabledCategories: Flow<Set<BackupCategory>> = context.dataStore.data.map { prefs ->
        val stored = prefs[Keys.ENABLED_CATEGORIES]
        if (stored.isNullOrEmpty()) BackupCategory.values().toSet()
        else stored.mapNotNull { runCatching { BackupCategory.valueOf(it) }.getOrNull() }.toSet()
    }

    val authorizedTreeUris: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.AUTHORIZED_TREE_URIS] ?: emptySet() }

    /** Default on: AirDrive backs up everything under internal storage with no folder picking. */
    val scanWholeDevice: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.SCAN_WHOLE_DEVICE] ?: true }

    val includeSdCard: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.INCLUDE_SD_CARD] ?: true }

    val safQueuePurged: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.SAF_QUEUE_PURGED] ?: false }

    // ---------------------------------------------------------------- API credentials

    /**
     * The api_id/api_hash TDLib is initialised with: whatever the user typed in, falling back to
     * the pair baked in at build time. A published APK can therefore ship with none at all and
     * still work — every user brings their own from my.telegram.org.
     */
    val apiCredentials: Flow<ApiCredentials> = context.dataStore.data.map { prefs ->
        val ownId = prefs[Keys.API_ID]
        val ownHash = prefs[Keys.API_HASH]
        if (ownId != null && ownId != 0 && !ownHash.isNullOrBlank()) {
            ApiCredentials(ownId, ownHash, fromUser = true)
        } else {
            ApiCredentials(BuildConfig.TELEGRAM_API_ID, BuildConfig.TELEGRAM_API_HASH, fromUser = false)
        }
    }

    suspend fun setApiCredentials(apiId: Int, apiHash: String) {
        context.dataStore.edit {
            it[Keys.API_ID] = apiId
            it[Keys.API_HASH] = apiHash.trim()
        }
    }

    /** Falls back to the build's own credentials, if it has any. */
    suspend fun clearApiCredentials() {
        context.dataStore.edit {
            it.remove(Keys.API_ID)
            it.remove(Keys.API_HASH)
        }
    }

    // ---------------------------------------------------------------- destinations

    val destinationMode: Flow<DestinationMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.DESTINATION_MODE]?.let { runCatching { DestinationMode.valueOf(it) }.getOrNull() }
            ?: defaultMode(prefs)
    }

    val singleChatId: Flow<Long> = context.dataStore.data.map { it[Keys.SINGLE_CHAT_ID] ?: 0L }

    fun channelFor(category: BackupCategory): Flow<Long> =
        context.dataStore.data.map { prefs -> resolveChannel(prefs, category) }

    val allChannels: Flow<ChannelMap> = context.dataStore.data.map { prefs ->
        ChannelMap(BackupCategory.values().associateWith { resolveChannel(prefs, it) })
    }

    /** Everything the uploader needs to decide where a file goes, read in one pass. */
    val destination: Flow<DestinationConfig> = context.dataStore.data.map { prefs ->
        DestinationConfig(
            mode = prefs[Keys.DESTINATION_MODE]
                ?.let { runCatching { DestinationMode.valueOf(it) }.getOrNull() }
                ?: defaultMode(prefs),
            singleChatId = prefs[Keys.SINGLE_CHAT_ID] ?: 0L,
            perCategory = BackupCategory.values().associateWith { resolveChannel(prefs, it) }
        )
    }

    suspend fun setDestinationMode(mode: DestinationMode) {
        context.dataStore.edit { it[Keys.DESTINATION_MODE] = mode.name }
    }

    suspend fun setSingleChatId(chatId: Long) {
        context.dataStore.edit { it[Keys.SINGLE_CHAT_ID] = chatId }
    }

    suspend fun setChannel(category: BackupCategory, channelId: Long) {
        context.dataStore.edit { it[Keys.channelKey(category)] = channelId }
    }

    suspend fun setChannels(channels: Map<BackupCategory, Long>) {
        context.dataStore.edit { prefs ->
            channels.forEach { (cat, id) -> prefs[Keys.channelKey(cat)] = id }
        }
    }

    private fun defaultMode(prefs: Preferences): DestinationMode =
        if (generationOf(prefs) == 1) DestinationMode.PER_CATEGORY else DestinationMode.SAVED_MESSAGES

    private fun resolveChannel(prefs: Preferences, category: BackupCategory): Long {
        prefs[Keys.channelKey(category)]?.let { return it }
        if (generationOf(prefs) == 1) return LegacyChannels.map[category] ?: 0L
        return 0L
    }

    /**
     * Reading the generation has to work even before [pinInstallGeneration] has run, so the
     * fallback repeats the same test: an install that has already onboarded or signed in existed
     * before this version and keeps the old defaults.
     */
    private fun generationOf(prefs: Preferences): Int =
        prefs[Keys.INSTALL_GENERATION]
            ?: if (prefs[Keys.ONBOARDING_DONE] == true || prefs[Keys.TELEGRAM_LOGGED_IN] == true) 1 else 2

    /** Called once at process start, before the user can change anything. */
    suspend fun pinInstallGeneration() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.INSTALL_GENERATION] == null) {
                prefs[Keys.INSTALL_GENERATION] = generationOf(prefs)
            }
        }
    }

    // ---------------------------------------------------------------- run policy

    /**
     * Replaces the old pair of booleans, which allowed "Wi-Fi only" and "Allow mobile data" to be
     * on at the same time. Existing installs are read through their old values once.
     */
    val networkPolicy: Flow<NetworkPolicy> = context.dataStore.data.map { prefs ->
        prefs[Keys.NETWORK_POLICY]?.let { runCatching { NetworkPolicy.valueOf(it) }.getOrNull() }
            ?: when {
                prefs[Keys.ALLOW_MOBILE_DATA] == true -> NetworkPolicy.ANY
                prefs[Keys.WIFI_ONLY] == false -> NetworkPolicy.ANY
                else -> NetworkPolicy.WIFI_ONLY
            }
    }

    val uploadOrder: Flow<UploadOrder> = context.dataStore.data.map { prefs ->
        prefs[Keys.UPLOAD_ORDER]?.let { runCatching { UploadOrder.valueOf(it) }.getOrNull() }
            ?: UploadOrder.OLDEST_FIRST
    }

    suspend fun setNetworkPolicy(policy: NetworkPolicy) {
        context.dataStore.edit { it[Keys.NETWORK_POLICY] = policy.name }
    }

    suspend fun setUploadOrder(order: UploadOrder) {
        context.dataStore.edit { it[Keys.UPLOAD_ORDER] = order.name }
    }

    // ---------------------------------------------------------------- scan rules

    /** Lower-case path fragments; any file whose path contains one is never queued. */
    val excludedPaths: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.EXCLUDED_PATHS] ?: emptySet() }

    /** 0 = no cap. Anything larger is skipped rather than failed. */
    val maxFileSizeMb: Flow<Long> = context.dataStore.data.map { it[Keys.MAX_FILE_SIZE_MB] ?: 0L }

    val captionTemplate: Flow<String> =
        context.dataStore.data.map { it[Keys.CAPTION_TEMPLATE] ?: DEFAULT_CAPTION_TEMPLATE }

    suspend fun addExcludedPath(fragment: String) {
        val clean = fragment.trim().trimEnd('/').lowercase()
        if (clean.isEmpty()) return
        context.dataStore.edit { prefs ->
            prefs[Keys.EXCLUDED_PATHS] = (prefs[Keys.EXCLUDED_PATHS] ?: emptySet()) + clean
        }
    }

    suspend fun removeExcludedPath(fragment: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EXCLUDED_PATHS] = (prefs[Keys.EXCLUDED_PATHS] ?: emptySet()) - fragment
        }
    }

    suspend fun setMaxFileSizeMb(mb: Long) {
        context.dataStore.edit { it[Keys.MAX_FILE_SIZE_MB] = mb.coerceAtLeast(0L) }
    }

    suspend fun setCaptionTemplate(template: String) {
        context.dataStore.edit { it[Keys.CAPTION_TEMPLATE] = template }
    }

    // ---------------------------------------------------------------- simple setters

    suspend fun setAutoBackupEnabled(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_BACKUP_ENABLED] = v }
    suspend fun setChargingOnly(v: Boolean) = context.dataStore.edit { it[Keys.CHARGING_ONLY] = v }
    suspend fun setBatteryConscious(v: Boolean) = context.dataStore.edit { it[Keys.BATTERY_CONSCIOUS] = v }
    suspend fun setIncludeSmallFiles(v: Boolean) = context.dataStore.edit { it[Keys.INCLUDE_SMALL_FILES] = v }
    suspend fun setBackupFrequencyHours(v: Long) = context.dataStore.edit { it[Keys.BACKUP_FREQUENCY_HOURS] = v }
    suspend fun setOnboardingDone(v: Boolean) = context.dataStore.edit { it[Keys.ONBOARDING_DONE] = v }
    suspend fun setTelegramLoggedIn(v: Boolean) = context.dataStore.edit { it[Keys.TELEGRAM_LOGGED_IN] = v }
    suspend fun setScanWholeDevice(v: Boolean) = context.dataStore.edit { it[Keys.SCAN_WHOLE_DEVICE] = v }
    suspend fun setIncludeSdCard(v: Boolean) = context.dataStore.edit { it[Keys.INCLUDE_SD_CARD] = v }
    suspend fun setSafQueuePurged(v: Boolean) = context.dataStore.edit { it[Keys.SAF_QUEUE_PURGED] = v }
    suspend fun setAutoRetryFailed(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_RETRY_FAILED] = v }

    suspend fun setEnabledCategories(categories: Set<BackupCategory>) {
        context.dataStore.edit { it[Keys.ENABLED_CATEGORIES] = categories.map { c -> c.name }.toSet() }
    }

    suspend fun addAuthorizedTreeUri(uri: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.AUTHORIZED_TREE_URIS] ?: emptySet()
            prefs[Keys.AUTHORIZED_TREE_URIS] = current + uri
        }
    }

    suspend fun removeAuthorizedTreeUri(uri: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.AUTHORIZED_TREE_URIS] ?: emptySet()
            prefs[Keys.AUTHORIZED_TREE_URIS] = current - uri
        }
    }

    /** Plain-text snapshot of the settings worth carrying to another phone. */
    suspend fun exportSummary(): String {
        val creds = apiCredentials.first()
        val dest = destination.first()
        return buildString {
            appendLine("# AirDrive settings")
            appendLine("api_credentials=${if (creds.fromUser) "user-supplied" else "build-supplied"}")
            appendLine("destination_mode=${dest.mode.name}")
            appendLine("single_chat_id=${dest.singleChatId}")
            dest.perCategory.forEach { (cat, id) -> appendLine("channel_${cat.name}=$id") }
            appendLine("network_policy=${networkPolicy.first().name}")
            appendLine("upload_order=${uploadOrder.first().name}")
            appendLine("charging_only=${chargingOnly.first()}")
            appendLine("battery_conscious=${batteryConscious.first()}")
            appendLine("frequency_hours=${backupFrequencyHours.first()}")
            appendLine("scan_whole_device=${scanWholeDevice.first()}")
            appendLine("include_sd_card=${includeSdCard.first()}")
            appendLine("include_small_files=${includeSmallFiles.first()}")
            appendLine("max_file_size_mb=${maxFileSizeMb.first()}")
            appendLine("enabled_categories=${enabledCategories.first().joinToString(",") { it.name }}")
            appendLine("excluded_paths=${excludedPaths.first().joinToString(",")}")
        }
    }
}
