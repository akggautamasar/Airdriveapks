package com.airdrive.backup.data.backup

import android.content.Context
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.LocalState
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.data.prefs.DestinationMode
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.telegram.TdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class ChannelSyncResult(
    val channels: Int,
    val messagesScanned: Int,
    val filesImported: Int,
    val alreadyIndexed: Int,
    val failedChannels: Int,
    val manifestEntries: Int
)

/** Imports the complete real contents of every configured Telegram channel into AirDrive's inventory. */
class TelegramChannelSync private constructor(private val context: Context) {
    private val dao = AppDatabase.get(context).fileRecordDao()
    private val settings = SettingsStore(context)
    private val tdClient = TdClient.get(context)

    suspend fun syncConfiguredChannels(onProgress: (String) -> Unit = {}): ChannelSyncResult = withContext(Dispatchers.IO) {
        if (!tdClient.awaitReady(45_000)) return@withContext ChannelSyncResult(0, 0, 0, 0, 1, dao.uploadedCount())
        val destination = settings.destination.first()
        val channels = when (destination.mode) {
            DestinationMode.SAVED_MESSAGES -> listOf(tdClient.savedMessagesChatId())
            DestinationMode.SINGLE_CHAT -> listOf(destination.singleChatId).filter { it != 0L }
            DestinationMode.PER_CATEGORY -> destination.perCategory.values.filter { it != 0L }.distinct()
        }
        var scanned = 0
        var imported = 0
        var existing = 0
        var failed = 0
        for ((index, chatId) in channels.withIndex()) {
            try {
                onProgress("Scanning channel ${index + 1}/${channels.size} • $chatId")
                val channelFiles = tdClient.scanChannelFiles(chatId) { count ->
                    onProgress("Channel ${index + 1}/${channels.size} • scanned $count files")
                }
                scanned += channelFiles.size
                for (remote in channelFiles) {
                    if (dao.findByTelegramMessage(remote.chatId, remote.messageId) != null) { existing++; continue }
                    val category = runCatching { BackupCategory.valueOf(remote.categoryName) }.getOrDefault(BackupCategory.OTHER_FILES)
                    val row = FileRecord(
                        uri = "telegram://${remote.chatId}/${remote.messageId}",
                        displayName = remote.fileName,
                        sizeBytes = remote.sizeBytes,
                        modifiedAtMillis = remote.dateMillis,
                        category = category,
                        fingerprint = "telegram:${remote.chatId}:${remote.messageId}",
                        status = UploadStatus.UPLOADED,
                        destinationChannelId = remote.chatId,
                        telegramMessageId = remote.messageId,
                        addedAtMillis = remote.dateMillis,
                        uploadedAtMillis = remote.dateMillis,
                        localState = LocalState.UNKNOWN
                    )
                    if (dao.insert(row) != -1L) imported++ else existing++
                }
                onProgress("Channel ${index + 1}/${channels.size} complete • ${channelFiles.size} files • $imported added so far")
                // A completed channel is checkpointed into Saved Messages before the next channel starts.
                runCatching { ManifestSync.get(context).sync() }
            } catch (e: Exception) {
                failed++
                onProgress("Channel ${index + 1}/${channels.size} failed • ${e.message ?: "unknown error"}")
            }
        }
        // Final authoritative manifest checkpoint after all connected channels have been reconciled.
        runCatching { ManifestSync.get(context).sync() }
        val manifestEntries = dao.uploadedCount()
        onProgress("Inventory complete • $scanned files found • $imported added • $existing already indexed • $manifestEntries in manifest")
        ChannelSyncResult(channels.size, scanned, imported, existing, failed, manifestEntries)
    }

    companion object {
        @Volatile private var instance: TelegramChannelSync? = null
        fun get(context: Context): TelegramChannelSync = instance ?: synchronized(this) {
            instance ?: TelegramChannelSync(context.applicationContext).also { instance = it }
        }
    }
}
