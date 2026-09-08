package com.airdrive.backup.data.backup

import android.content.Context
import android.util.Log
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.telegram.TdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Keeps a portable inventory manifest in Saved Messages. Room is the live inventory; this
 * manifest is the recovery source that survives reinstall and is reconciled back into Room.
 */
class ManifestSync(private val context: Context) {
    private val tag = "AirDrive.Manifest"
    private val dao = AppDatabase.get(context).fileRecordDao()
    private val settings = SettingsStore(context)
    private val tdClient = TdClient.get(context)

    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!tdClient.awaitReady(15_000)) return@withContext false
            val records = mutableListOf<FileRecord>()
            var offset = 0
            while (true) {
                val page = dao.uploadedPageById(PAGE_SIZE, offset)
                if (page.isEmpty()) break
                records += page
                offset += page.size
            }
            if (records.isEmpty()) {
                Log.i(tag, "nothing uploaded yet, skipping manifest sync")
                return@withContext true
            }
            val dest = settings.destination.first()
            val template = settings.captionTemplate.first()
            val manifest = BackupManifest.fromRecords(records, dest.mode, dest.singleChatId, dest.perCategory, template)
            val file = writeGzipped(manifest.toJson())
            val chatId = tdClient.savedMessagesChatId()
            val caption = "$MANIFEST_MARKER\n" +
                "🔒 AirDrive Backup Data — DO NOT DELETE\n" +
                "${manifest.entryCount} file(s) tracked • updated automatically after every backup run.\n" +
                "This message lets AirDrive recognise your backed-up files again if you reinstall the app. " +
                "Deleting it just means a reinstall will re-scan instead of remembering — your uploaded files themselves are unaffected either way."
            val cached = settings.manifestLocation.first()
            var edited = false
            if (cached != null) {
                edited = runCatching { tdClient.editMessageDocument(cached.first, cached.second, file.absolutePath, caption) }.isSuccess
            }
            if (!edited) {
                val existing = runCatching { tdClient.findLatestOwnDocument(MANIFEST_MARKER) }.getOrNull()
                if (existing != null) {
                    edited = runCatching { tdClient.editMessageDocument(chatId, existing.id, file.absolutePath, caption) }.isSuccess
                    if (edited) settings.setManifestLocation(chatId, existing.id)
                }
            }
            if (!edited) {
                val messageId = tdClient.uploadFile(file.absolutePath, chatId, caption, file.length())
                settings.setManifestLocation(chatId, messageId)
                runCatching { tdClient.pinMessage(chatId, messageId) }
                    .onFailure { Log.w(tag, "could not pin manifest message: ${it.message}") }
            }
            file.delete()
            Log.i(tag, "manifest synced: ${manifest.entryCount} file(s)")
            true
        } catch (e: Exception) {
            Log.w(tag, "manifest sync failed: ${e.message}")
            false
        }
    }

    /**
     * Rebuilds missing Room rows from the manifest already stored in Telegram.
     * Supports both the current gzip file and older/plain JSON manifest files.
     * If Telegram search cannot find the document, Saved Messages history is scanned for a
     * document whose filename contains "airdrive-manifest".
     */
    suspend fun reconcileAvailableManifest(): Int = withContext(Dispatchers.IO) {
        try {
            if (!tdClient.awaitReady(30_000)) return@withContext 0

            var message = runCatching { tdClient.findLatestOwnDocument(MANIFEST_MARKER) }.getOrNull()
            if (message == null) {
                val savedChatId = tdClient.savedMessagesChatId()
                val candidates = tdClient.scanChannelFiles(savedChatId)
                    .asReversed()
                    .filter { it.fileName.lowercase().contains("airdrive-manifest") }
                val candidate = candidates.lastOrNull()
                if (candidate != null) {
                    // GetMessage/downloadMessageFile needs only the chat and message identity.
                    message = null
                    settings.setManifestLocation(candidate.chatId, candidate.messageId)
                    val downloaded = tdClient.downloadMessageFile(candidate.chatId, candidate.messageId)
                    return@withContext importManifestFile(File(downloaded.path), candidate.chatId, candidate.messageId)
                }
            }

            if (message == null) return@withContext 0
            settings.setManifestLocation(message.chatId, message.id)
            val downloaded = tdClient.downloadFile(message)
            importManifestFile(File(downloaded.path), message.chatId, message.id)
        } catch (e: Exception) {
            Log.w(tag, "manifest reconciliation failed: ${e.message}")
            0
        }
    }

    private suspend fun importManifestFile(file: File, chatId: Long, messageId: Long): Int {
        try {
            val manifest = readManifest(file)
            val rows = manifest.entries.filter { it.chatId != 0L && it.messageId != 0L }
            var inserted = 0
            var existing = 0
            rows.chunked(200).forEach { chunk ->
                val missing = chunk.filter { entry ->
                    dao.findByTelegramMessage(entry.chatId, entry.messageId) == null &&
                        dao.findByFingerprint(entry.fingerprint) == null
                }
                existing += chunk.size - missing.size
                if (missing.isNotEmpty()) {
                    inserted += dao.insertRestored(missing.map { it.toUploadedRecord() }).count { it != -1L }
                }
            }
            file.delete()
            Log.i(tag, "manifest reconciliation: ${rows.size} entries, $inserted imported, $existing already indexed")
            return inserted
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    /** Accept current gzip manifests and older/plain JSON files. */
    private fun readManifest(file: File): BackupManifest {
        val bytes = file.readBytes()
        val jsonText = runCatching {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
        }.getOrElse {
            bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        }
        return BackupManifest.parse(JSONObject(jsonText))
    }

    suspend fun restoreIfAvailable(force: Boolean = false): RestoreResult = withContext(Dispatchers.IO) {
        try {
            if (!force && dao.totalRowCount() > 0) return@withContext RestoreResult.NothingToDo
            if (!tdClient.awaitReady(30_000)) return@withContext RestoreResult.NotSignedIn
            val message = tdClient.findLatestOwnDocument(MANIFEST_MARKER) ?: return@withContext RestoreResult.NoManifestFound
            settings.setManifestLocation(message.chatId, message.id)
            val downloaded = tdClient.downloadFile(message)
            val manifest = readManifest(File(downloaded.path))
            val rows = manifest.entries.map { it.toUploadedRecord() }
            var inserted = 0
            rows.chunked(200).forEach { chunk -> inserted += dao.insertRestored(chunk).count { it != -1L } }
            if (manifest.singleChatId != 0L) settings.setSingleChatId(manifest.singleChatId)
            if (manifest.perCategoryChannels.isNotEmpty()) settings.setChannels(manifest.perCategoryChannels)
            settings.setDestinationMode(manifest.destinationMode)
            if (manifest.captionTemplate.isNotBlank()) settings.setCaptionTemplate(manifest.captionTemplate)
            Log.i(tag, "restored $inserted file(s) from manifest dated ${manifest.generatedAtMillis}")
            RestoreResult.Restored(inserted, manifest.generatedAtMillis)
        } catch (e: Exception) {
            Log.w(tag, "manifest restore failed: ${e.message}")
            RestoreResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun writeGzipped(json: JSONObject): File {
        val dir = File(context.cacheDir, "manifest").apply { mkdirs() }
        val file = File(dir, "airdrive-manifest.json.gz")
        GZIPOutputStream(file.outputStream()).use { gz -> gz.write(json.toString().toByteArray(Charsets.UTF_8)) }
        return file
    }

    sealed class RestoreResult {
        object NothingToDo : RestoreResult()
        object NotSignedIn : RestoreResult()
        object NoManifestFound : RestoreResult()
        data class Restored(val fileCount: Int, val manifestDateMillis: Long) : RestoreResult()
        data class Failed(val reason: String) : RestoreResult()
    }

    companion object {
        private const val PAGE_SIZE = 500
        @Volatile private var instance: ManifestSync? = null
        fun get(context: Context): ManifestSync = instance ?: synchronized(this) {
            instance ?: ManifestSync(context.applicationContext).also { instance = it }
        }
    }
}
