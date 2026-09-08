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
                val files = tdClient.scanChannelFiles(chatId) { count ->
                    scanned = scanned - (filesCountForChannelNotKnownYet(index)) + count
                    onProgress("Channel ${index + 1}/${channels.size} • scanned $count files")
                }
                // Persist this channel's entire inventory before moving to the next channel.
                for (remote in files) {
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
                scanned += files.size
                onProgress("Channel ${index + 1}/${channels.size} complete • ${files.size} files")
                // Checkpoint the manifest after every channel. If the app/process disappears,
                // completed channels are already represented in Saved Messages.
                runCatching { ManifestSync.get(context).sync() }
            } catch (e: Exception) {
                failed++
                onProgress("Channel ${index + 1}/${channels.size} failed • ${e.message ?: "unknown error"}")
            }
        }
        // Final authoritative manifest checkpoint after all channels.
        runCatching { ManifestSync.get(context).sync() }
        val manifestEntries = dao.uploadedCount()
        onProgress("Inventory complete • $scanned files found • $imported added • $existing already indexed • $manifestEntries in manifest")
        ChannelSyncResult(channels.size, scanned, imported, existing, failed, manifestEntries)
    }

    // Kept as a separate function so progress remains cheap and deterministic; the scanner's
    // callback is informational while the final channel list is authoritative for totals.
    private fun filesCountForChannelNotKnownYet(index: Int): Int = 0

    companion object {
        @Volatile private var instance: TelegramChannelSync? = null
        fun get(context: Context): TelegramChannelSync = instance ?: synchronized(this) {
            instance ?: TelegramChannelSync(context.applicationContext).also { instance = it }
        }
    }
}
