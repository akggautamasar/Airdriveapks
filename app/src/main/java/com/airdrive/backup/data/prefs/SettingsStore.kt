package com.airdrive.backup.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.airdrive.backup.data.db.BackupCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "airdrive_settings")

data class ChannelMap(val perCategory: Map<BackupCategory, Long>)

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
}

/** Default channel IDs mirror the original Python prototype; editable in Settings. */
object DefaultChannels {
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

class SettingsStore(private val context: Context) {

    val autoBackupEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AUTO_BACKUP_ENABLED] ?: true }

    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { it[Keys.WIFI_ONLY] ?: true }
    val chargingOnly: Flow<Boolean> = context.dataStore.data.map { it[Keys.CHARGING_ONLY] ?: false }
    val allowMobileData: Flow<Boolean> = context.dataStore.data.map { it[Keys.ALLOW_MOBILE_DATA] ?: false }
    val batteryConscious: Flow<Boolean> = context.dataStore.data.map { it[Keys.BATTERY_CONSCIOUS] ?: true }
    val includeSmallFiles: Flow<Boolean> = context.dataStore.data.map { it[Keys.INCLUDE_SMALL_FILES] ?: false }
    val backupFrequencyHours: Flow<Long> = context.dataStore.data.map { it[Keys.BACKUP_FREQUENCY_HOURS] ?: 6L }
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }
    val telegramLoggedIn: Flow<Boolean> = context.dataStore.data.map { it[Keys.TELEGRAM_LOGGED_IN] ?: false }

    val enabledCategories: Flow<Set<BackupCategory>> = context.dataStore.data.map { prefs ->
        val stored = prefs[Keys.ENABLED_CATEGORIES]
        if (stored.isNullOrEmpty()) BackupCategory.values().toSet()
        else stored.mapNotNull { runCatching { BackupCategory.valueOf(it) }.getOrNull() }.toSet()
    }

    val authorizedTreeUris: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.AUTHORIZED_TREE_URIS] ?: emptySet() }

    fun channelFor(category: BackupCategory): Flow<Long> =
        context.dataStore.data.map { it[Keys.channelKey(category)] ?: DefaultChannels.map.getValue(category) }

    val allChannels: Flow<ChannelMap> = context.dataStore.data.map { prefs ->
        ChannelMap(BackupCategory.values().associateWith { cat ->
            prefs[Keys.channelKey(cat)] ?: DefaultChannels.map.getValue(cat)
        })
    }

    suspend fun setChannel(category: BackupCategory, channelId: Long) {
        context.dataStore.edit { it[Keys.channelKey(category)] = channelId }
    }

    suspend fun setAutoBackupEnabled(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_BACKUP_ENABLED] = v }
    suspend fun setWifiOnly(v: Boolean) = context.dataStore.edit { it[Keys.WIFI_ONLY] = v }
    suspend fun setChargingOnly(v: Boolean) = context.dataStore.edit { it[Keys.CHARGING_ONLY] = v }
    suspend fun setAllowMobileData(v: Boolean) = context.dataStore.edit { it[Keys.ALLOW_MOBILE_DATA] = v }
    suspend fun setBatteryConscious(v: Boolean) = context.dataStore.edit { it[Keys.BATTERY_CONSCIOUS] = v }
    suspend fun setIncludeSmallFiles(v: Boolean) = context.dataStore.edit { it[Keys.INCLUDE_SMALL_FILES] = v }
    suspend fun setBackupFrequencyHours(v: Long) = context.dataStore.edit { it[Keys.BACKUP_FREQUENCY_HOURS] = v }
    suspend fun setOnboardingDone(v: Boolean) = context.dataStore.edit { it[Keys.ONBOARDING_DONE] = v }
    suspend fun setTelegramLoggedIn(v: Boolean) = context.dataStore.edit { it[Keys.TELEGRAM_LOGGED_IN] = v }

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
}
