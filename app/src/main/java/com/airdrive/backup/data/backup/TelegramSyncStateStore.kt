package com.airdrive.backup.data.backup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.telegramSyncStore by preferencesDataStore(name = "airdrive_telegram_sync")

data class TelegramSyncState(
    val running: Boolean = false,
    val status: String = "Ready to import Telegram files",
    val channels: Int = 0,
    val currentChannel: Int = 0,
    val filesFound: Int = 0,
    val imported: Int = 0,
    val alreadyIndexed: Int = 0,
    val manifestEntries: Int = 0,
    val failedChannels: Int = 0,
    val finishedAt: Long = 0L
)

class TelegramSyncStateStore(private val context: Context) {
    val state: Flow<TelegramSyncState> = context.telegramSyncStore.data.map { p ->
        TelegramSyncState(
            running = p[Keys.RUNNING] ?: false,
            status = p[Keys.STATUS] ?: "Ready to import Telegram files",
            channels = p[Keys.CHANNELS] ?: 0,
            currentChannel = p[Keys.CURRENT_CHANNEL] ?: 0,
            filesFound = p[Keys.FILES_FOUND] ?: 0,
            imported = p[Keys.IMPORTED] ?: 0,
            alreadyIndexed = p[Keys.ALREADY] ?: 0,
            manifestEntries = p[Keys.MANIFEST] ?: 0,
            failedChannels = p[Keys.FAILED] ?: 0,
            finishedAt = p[Keys.FINISHED_AT] ?: 0L
        )
    }

    suspend fun running(channels: Int, status: String) = edit {
        this[Keys.RUNNING] = true; this[Keys.CHANNELS] = channels; this[Keys.STATUS] = status
    }
    suspend fun progress(currentChannel: Int, filesFound: Int, status: String) = edit {
        this[Keys.RUNNING] = true; this[Keys.CURRENT_CHANNEL] = currentChannel; this[Keys.FILES_FOUND] = filesFound; this[Keys.STATUS] = status
    }
    suspend fun complete(channels: Int, filesFound: Int, imported: Int, already: Int, failed: Int, manifest: Int, status: String) = edit {
        this[Keys.RUNNING] = false; this[Keys.CHANNELS] = channels; this[Keys.CURRENT_CHANNEL] = channels; this[Keys.FILES_FOUND] = filesFound
        this[Keys.IMPORTED] = imported; this[Keys.ALREADY] = already; this[Keys.FAILED] = failed; this[Keys.MANIFEST] = manifest
        this[Keys.STATUS] = status; this[Keys.FINISHED_AT] = System.currentTimeMillis()
    }
    suspend fun failed(status: String) = edit { this[Keys.RUNNING] = false; this[Keys.STATUS] = status; this[Keys.FINISHED_AT] = System.currentTimeMillis() }

    private suspend fun edit(block: androidx.datastore.preferences.core.MutablePreferences.() -> Unit) {
        context.telegramSyncStore.edit { block() }
    }

    private object Keys {
        val RUNNING = booleanPreferencesKey("running")
        val STATUS = stringPreferencesKey("status")
        val CHANNELS = intPreferencesKey("channels")
        val CURRENT_CHANNEL = intPreferencesKey("current_channel")
        val FILES_FOUND = intPreferencesKey("files_found")
        val IMPORTED = intPreferencesKey("imported")
        val ALREADY = intPreferencesKey("already_indexed")
        val MANIFEST = intPreferencesKey("manifest_entries")
        val FAILED = intPreferencesKey("failed_channels")
        val FINISHED_AT = androidx.datastore.preferences.core.longPreferencesKey("finished_at")
    }
}
