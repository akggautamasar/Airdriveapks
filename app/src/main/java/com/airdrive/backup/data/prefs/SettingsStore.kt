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
import com.airdrive.backup.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "airdrive_settings")

data class ChannelMap(val perCategory: Map<BackupCategory, Long>)
enum class DestinationMode { SAVED_MESSAGES, SINGLE_CHAT, PER_CATEGORY }
enum class NetworkPolicy { WIFI_ONLY, NOT_ROAMING, ANY }
enum class UploadOrder { OLDEST_FIRST, NEWEST_FIRST, SMALLEST_FIRST }
data class ApiCredentials(val apiId: Int, val apiHash: String, val fromUser: Boolean) { val isUsable: Boolean get() = apiId != 0 && apiHash.isNotBlank() }
data class DestinationConfig(val mode: DestinationMode, val singleChatId: Long, val perCategory: Map<BackupCategory, Long>) { val needsSetup: Boolean get() = when (mode) { DestinationMode.SAVED_MESSAGES -> false; DestinationMode.SINGLE_CHAT -> singleChatId == 0L; DestinationMode.PER_CATEGORY -> perCategory.values.all { it == 0L } } }

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
    val SCAN_WHOLE_DEVICE = booleanPreferencesKey("scan_whole_device")
    val INCLUDE_SD_CARD = booleanPreferencesKey("include_sd_card")
    val SAF_QUEUE_PURGED = booleanPreferencesKey("saf_queue_purged")
    val API_ID = intPreferencesKey("telegram_api_id")
    val API_HASH = stringPreferencesKey("telegram_api_hash")
    val MANIFEST_CHAT_ID = longPreferencesKey("manifest_chat_id")
    val MANIFEST_MESSAGE_ID = longPreferencesKey("manifest_message_id")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val DESTINATION_MODE = stringPreferencesKey("destination_mode")
    val SINGLE_CHAT_ID = longPreferencesKey("single_chat_id")
    val NETWORK_POLICY = stringPreferencesKey("network_policy")
    val UPLOAD_ORDER = stringPreferencesKey("upload_order")
    val EXCLUDED_PATHS = stringSetPreferencesKey("excluded_paths")
    val MAX_FILE_SIZE_MB = longPreferencesKey("max_file_size_mb")
    val CAPTION_TEMPLATE = stringPreferencesKey("caption_template")
    val AUTO_RETRY_FAILED = booleanPreferencesKey("auto_retry_failed")
    val AUTO_DELETE_MISSING_ENABLED = booleanPreferencesKey("auto_delete_missing_enabled")
    val AUTO_DELETE_MISSING_DAYS = longPreferencesKey("auto_delete_missing_days")
    val INSTALL_GENERATION = intPreferencesKey("install_generation")
    /** Optional app-level authentication. Off by default so existing users are never interrupted. */
    val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
}

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
    val autoBackupEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_BACKUP_ENABLED] ?: true }
    val chargingOnly: Flow<Boolean> = context.dataStore.data.map { it[Keys.CHARGING_ONLY] ?: false }
    val batteryConscious: Flow<Boolean> = context.dataStore.data.map { it[Keys.BATTERY_CONSCIOUS] ?: true }
    val includeSmallFiles: Flow<Boolean> = context.dataStore.data.map { it[Keys.INCLUDE_SMALL_FILES] ?: false }
    val backupFrequencyHours: Flow<Long> = context.dataStore.data.map { it[Keys.BACKUP_FREQUENCY_HOURS] ?: 6L }
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }
    val telegramLoggedIn: Flow<Boolean> = context.dataStore.data.map { it[Keys.TELEGRAM_LOGGED_IN] ?: false }
    val autoRetryFailed: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_RETRY_FAILED] ?: true }
    val appLockEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.APP_LOCK_ENABLED] ?: false }
    suspend fun setAppLockEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.APP_LOCK_ENABLED] = enabled } }

    val enabledCategories: Flow<Set<BackupCategory>> = context.dataStore.data.map { prefs -> val stored = prefs[Keys.ENABLED_CATEGORIES]; if (stored.isNullOrEmpty()) BackupCategory.values().toSet() else stored.mapNotNull { runCatching { BackupCategory.valueOf(it) }.getOrNull() }.toSet() }
    val authorizedTreeUris: Flow<Set<String>> = context.dataStore.data.map { it[Keys.AUTHORIZED_TREE_URIS] ?: emptySet() }
    val scanWholeDevice: Flow<Boolean> = context.dataStore.data.map { it[Keys.SCAN_WHOLE_DEVICE] ?: true }
    val includeSdCard: Flow<Boolean> = context.dataStore.data.map { it[Keys.INCLUDE_SD_CARD] ?: true }
    val safQueuePurged: Flow<Boolean> = context.dataStore.data.map { it[Keys.SAF_QUEUE_PURGED] ?: false }

    val apiCredentials: Flow<ApiCredentials> = context.dataStore.data.map { prefs -> val ownId = prefs[Keys.API_ID]; val ownHash = prefs[Keys.API_HASH]; if (ownId != null && ownId != 0 && !ownHash.isNullOrBlank()) ApiCredentials(ownId, ownHash, true) else ApiCredentials(BuildConfig.TELEGRAM_API_ID, BuildConfig.TELEGRAM_API_HASH, false) }
    suspend fun setApiCredentials(apiId: Int, apiHash: String) { context.dataStore.edit { it[Keys.API_ID] = apiId; it[Keys.API_HASH] = apiHash.trim() } }
    suspend fun clearApiCredentials() { context.dataStore.edit { it.remove(Keys.API_ID); it.remove(Keys.API_HASH) } }

    val manifestLocation: Flow<Pair<Long, Long>?> = context.dataStore.data.map { prefs -> val chatId = prefs[Keys.MANIFEST_CHAT_ID]; val messageId = prefs[Keys.MANIFEST_MESSAGE_ID]; if (chatId != null && messageId != null && chatId != 0L && messageId != 0L) chatId to messageId else null }
    suspend fun setManifestLocation(chatId: Long, messageId: Long) { context.dataStore.edit { it[Keys.MANIFEST_CHAT_ID] = chatId; it[Keys.MANIFEST_MESSAGE_ID] = messageId } }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs -> prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM }
    suspend fun setThemeMode(mode: ThemeMode) { context.dataStore.edit { it[Keys.THEME_MODE] = mode.name } }

    val destinationMode: Flow<DestinationMode> = context.dataStore.data.map { prefs -> prefs[Keys.DESTINATION_MODE]?.let { runCatching { DestinationMode.valueOf(it) }.getOrNull() } ?: defaultMode(prefs) }
    val singleChatId: Flow<Long> = context.dataStore.data.map { it[Keys.SINGLE_CHAT_ID] ?: 0L }
    fun channelFor(category: BackupCategory): Flow<Long> = context.dataStore.data.map { prefs -> resolveChannel(prefs, category) }
    val allChannels: Flow<ChannelMap> = context.dataStore.data.map { prefs -> ChannelMap(BackupCategory.values().associateWith { resolveChannel(prefs, it) }) }
    val destination: Flow<DestinationConfig> = context.dataStore.data.map { prefs -> DestinationConfig(prefs[Keys.DESTINATION_MODE]?.let { runCatching { DestinationMode.valueOf(it) }.getOrNull() } ?: defaultMode(prefs), prefs[Keys.SINGLE_CHAT_ID] ?: 0L, BackupCategory.values().associateWith { resolveChannel(prefs, it) }) }
    suspend fun setDestinationMode(mode: DestinationMode) { context.dataStore.edit { it[Keys.DESTINATION_MODE] = mode.name } }
    suspend fun setSingleChatId(chatId: Long) { context.dataStore.edit { it[Keys.SINGLE_CHAT_ID] = chatId } }
    suspend fun setChannel(category: BackupCategory, channelId: Long) { context.dataStore.edit { it[Keys.channelKey(category)] = channelId } }
    suspend fun setChannels(channels: Map<BackupCategory, Long>) { context.dataStore.edit { prefs -> channels.forEach { (cat, id) -> prefs[Keys.channelKey(cat)] = id } } }
    private fun defaultMode(prefs: Preferences): DestinationMode = if (generationOf(prefs) == 1) DestinationMode.PER_CATEGORY else DestinationMode.SAVED_MESSAGES
    private fun resolveChannel(prefs: Preferences, category: BackupCategory): Long { prefs[Keys.channelKey(category)]?.let { return it }; if (generationOf(prefs) == 1) return LegacyChannels.map[category] ?: 0L; return 0L }
    private fun generationOf(prefs: Preferences): Int = prefs[Keys.INSTALL_GENERATION] ?: if (prefs[Keys.ONBOARDING_DONE] == true || prefs[Keys.TELEGRAM_LOGGED_IN] == true) 1 else 2
    suspend fun pinInstallGeneration() { context.dataStore.edit { prefs -> if (prefs[Keys.INSTALL_GENERATION] == null) prefs[Keys.INSTALL_GENERATION] = generationOf(prefs) } }

    val networkPolicy: Flow<NetworkPolicy> = context.dataStore.data.map { prefs -> prefs[Keys.NETWORK_POLICY]?.let { runCatching { NetworkPolicy.valueOf(it) }.getOrNull() } ?: when { prefs[Keys.ALLOW_MOBILE_DATA] == true -> NetworkPolicy.ANY; prefs[Keys.WIFI_ONLY] == false -> NetworkPolicy.ANY; else -> NetworkPolicy.WIFI_ONLY } }
    val uploadOrder: Flow<UploadOrder> = context.dataStore.data.map { prefs -> prefs[Keys.UPLOAD_ORDER]?.let { runCatching { UploadOrder.valueOf(it) }.getOrNull() } ?: UploadOrder.OLDEST_FIRST }
    suspend fun setNetworkPolicy(policy: NetworkPolicy) { context.dataStore.edit { it[Keys.NETWORK_POLICY] = policy.name } }
    suspend fun setUploadOrder(order: UploadOrder) { context.dataStore.edit { it[Keys.UPLOAD_ORDER] = order.name } }
    val excludedPaths: Flow<Set<String>> = context.dataStore.data.map { it[Keys.EXCLUDED_PATHS] ?: emptySet() }
    val maxFileSizeMb: Flow<Long> = context.dataStore.data.map { it[Keys.MAX_FILE_SIZE_MB] ?: 0L }
    val captionTemplate: Flow<String> = context.dataStore.data.map { it[Keys.CAPTION_TEMPLATE] ?: DEFAULT_CAPTION_TEMPLATE }
    suspend fun addExcludedPath(fragment: String) { val clean = fragment.trim().trimEnd('/').lowercase(); if (clean.isEmpty()) return; context.dataStore.edit { prefs -> prefs[Keys.EXCLUDED_PATHS] = (prefs[Keys.EXCLUDED_PATHS] ?: emptySet()) + clean } }
    suspend fun removeExcludedPath(fragment: String) { context.dataStore.edit { prefs -> prefs[Keys.EXCLUDED_PATHS] = (prefs[Keys.EXCLUDED_PATHS] ?: emptySet()) - fragment } }
    suspend fun setMaxFileSizeMb(mb: Long) { context.dataStore.edit { it[Keys.MAX_FILE_SIZE_MB] = mb.coerceAtLeast(0L) } }
    suspend fun setCaptionTemplate(template: String) { context.dataStore.edit { it[Keys.CAPTION_TEMPLATE] = template } }
    suspend fun setAutoRetryFailed(enabled: Boolean) { context.dataStore.edit { it[Keys.AUTO_RETRY_FAILED] = enabled } }
    suspend fun setAutoDeleteMissing(enabled: Boolean) { context.dataStore.edit { it[Keys.AUTO_DELETE_MISSING_ENABLED] = enabled } }
    val autoDeleteMissingEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_DELETE_MISSING_ENABLED] ?: false }
    val autoDeleteMissingDays: Flow<Long> = context.dataStore.data.map { it[Keys.AUTO_DELETE_MISSING_DAYS] ?: 30L }
    suspend fun setAutoDeleteMissingDays(days: Long) { context.dataStore.edit { it[Keys.AUTO_DELETE_MISSING_DAYS] = days.coerceAtLeast(1L) } }
    suspend fun setAutoBackupEnabled(enabled: Boolean) { context.dataStore.edit { it[Keys.AUTO_BACKUP_ENABLED] = enabled } }
    suspend fun setChargingOnly(enabled: Boolean) { context.dataStore.edit { it[Keys.CHARGING_ONLY] = enabled } }
    suspend fun setBatteryConscious(enabled: Boolean) { context.dataStore.edit { it[Keys.BATTERY_CONSCIOUS] = enabled } }
    suspend fun setIncludeSmallFiles(enabled: Boolean) { context.dataStore.edit { it[Keys.INCLUDE_SMALL_FILES] = enabled } }
    suspend fun setBackupFrequencyHours(hours: Long) { context.dataStore.edit { it[Keys.BACKUP_FREQUENCY_HOURS] = hours } }
    suspend fun setEnabledCategories(categories: Set<BackupCategory>) { context.dataStore.edit { it[Keys.ENABLED_CATEGORIES] = categories.map { c -> c.name }.toSet() } }
    suspend fun setScanWholeDevice(enabled: Boolean) { context.dataStore.edit { it[Keys.SCAN_WHOLE_DEVICE] = enabled } }
    suspend fun setIncludeSdCard(enabled: Boolean) { context.dataStore.edit { it[Keys.INCLUDE_SD_CARD] = enabled } }
    suspend fun setSafQueuePurged(enabled: Boolean) { context.dataStore.edit { it[Keys.SAF_QUEUE_PURGED] = enabled } }
}
