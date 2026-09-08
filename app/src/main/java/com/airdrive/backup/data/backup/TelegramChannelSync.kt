package com.airdrive.backup.data.backup

import android.content.Context
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.LocalState
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.telegram.TelegramChannelFile
import com.airdrive.backup.telegram.TdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Imports the real contents of configured Telegram channels into AirDrive's local inventory. */
data class ChannelSyncResult(val channels: Int, val messagesScanned: Int, val filesImported: Int, val alreadyIndexed: Int, val failedChannels: Int)

class TelegramChannelSync private constructor(private val context: Context) {
    private val dao = AppDatabase.get(context).fileRecordDao()
    private val settings = com.airdrive.backup.data.prefs.SettingsStore(context)
    private val tdClient = TdClient.get(context)

    suspend fun syncConfiguredChannels(onProgress: (String) -> Unit = {}): ChannelSyncResult = withContext(Dispatchers.IO) {
        if (!tdClient.awaitReady(45_000)) return@withContext ChannelSyncResult(0, 0, 0, 0, 1)
        val destination = settings.destination.first()
        val channels = when (destination.mode) {
            com.airdrive.backup.data.prefs.DestinationMode.SAVED_MESSAGES -> listOf(tdClient.savedMessagesChatId())
            com.airdrive.backup.data.prefs.DestinationMode.SINGLE_CHAT -> listOf(destination.singleChatId).filter { it != 0L }
            com.airdrive.backup.data.prefs.DestinationMode.PER_CATEGORY -> destination.perCategory.values.filter { it != 0L }.distinct()
        }
        var scanned = 0
        var imported = 0
        var existing = 0
        var failed = 0
        for (chatId in channels) {
            try {
                onProgress("Scanning Telegram channel $chatId…")
                val files = tdClient.scanChannelFiles(chatId) { count -> onProgress("Scanned $count files in Telegram…") }
                scanned += files.size
                for (remote in files) {
                    if (dao.findByTelegramMessage(remote.chatId, remote.messageId) != null) { existing++; continue }
                    val category = runCatching { BackupCategory.valueOf(remote.categoryName) }.getOrDefault(BackupCategory.OTHER_FILES)
                    val fingerprint = "telegram:${remote.chatId}:${remote.messageId}"
                    val row = FileRecord(
                        uri = "telegram://${remote.chatId}/${remote.messageId}",
                        displayName = remote.fileName,
                        sizeBytes = remote.sizeBytes,
                        modifiedAtMillis = remote.dateMillis,
                        category = category,
                        fingerprint = fingerprint,
                        status = UploadStatus.UPLOADED,
                        destinationChannelId = remote.chatId,
                        telegramMessageId = remote.messageId,
                        addedAtMillis = remote.dateMillis,
                        uploadedAtMillis = remote.dateMillis,
                        localState = LocalState.UNKNOWN
                    )
                    if (dao.insert(row) != -1L) imported++ else existing++
                }
            } catch (_: Exception) { failed++ }
        }
        // Rebuild the portable JSON snapshot from the now-complete Room inventory.
        runCatching { ManifestSync.get(context).sync() }
        ChannelSyncResult(channels.size, scanned, imported, existing, failed)
    }

    companion object {
        @Volatile private var instance: TelegramChannelSync? = null
        fun get(context: Context): TelegramChannelSync = instance ?: synchronized(this) {
            instance ?: TelegramChannelSync(context.applicationContext).also { instance = it }
        }
    }
}
