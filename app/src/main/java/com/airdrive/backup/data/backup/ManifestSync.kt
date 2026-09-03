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
 * Keeps a copy of "which files are already backed up, and where things were configured" sitting
 * inside the user's own Telegram Saved Messages. This is what lets AirDrive survive an
 * uninstall/reinstall: the app has no local state left at that point, but Telegram still has the
 * manifest, findable purely by [findLatestOwnDocument] with no locally stored id needed.
 */
class ManifestSync(private val context: Context) {

    private val tag = "AirDrive.Manifest"
    private val dao = AppDatabase.get(context).fileRecordDao()
    private val settings = SettingsStore(context)
    private val tdClient = TdClient.get(context)

    /**
     * Builds the manifest from the current DB + settings, uploads it to Saved Messages, and
     * pins the message so it survives Telegram's own "clear chat history" and stays easy to
     * find by eye too — pinned messages are Telegram's own equivalent of "do not delete".
     */
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
            val manifest = BackupManifest.fromRecords(
                records = records,
                destinationMode = dest.mode,
                singleChatId = dest.singleChatId,
                perCategoryChannels = dest.perCategory,
                captionTemplate = template
            )

            val file = writeGzipped(manifest.toJson())
            val chatId = tdClient.savedMessagesChatId()
            val caption = "$MANIFEST_MARKER\n" +
                "🔒 AirDrive Backup Data — DO NOT DELETE\n" +
                "${manifest.entryCount} file(s) tracked • updated automatically after every backup run.\n" +
                "This message lets AirDrive recognise your backed-up files again if you " +
                "reinstall the app. Deleting it just means a reinstall will re-scan instead " +
                "of remembering — your uploaded files themselves are unaffected either way."

            // Edit the same message every time rather than sending a fresh one, so Saved
            // Messages ends up with exactly one manifest document, not one per checkpoint.
            val cached = settings.manifestLocation.first()
            var edited = false
            if (cached != null) {
                edited = runCatching {
                    tdClient.editMessageDocument(cached.first, cached.second, file.absolutePath, caption)
                }.isSuccess
            }

            if (!edited) {
                // No cached location (fresh install, or the cached message id is stale) — look
                // for an existing manifest by search before giving up and sending a new one, so
                // a reinstall that already restored from an old manifest keeps editing that
                // same message rather than starting a second one.
                val existing = runCatching { tdClient.findLatestOwnDocument(MANIFEST_MARKER) }.getOrNull()
                if (existing != null) {
                    edited = runCatching {
                        tdClient.editMessageDocument(chatId, existing.id, file.absolutePath, caption)
                    }.isSuccess
                    if (edited) settings.setManifestLocation(chatId, existing.id)
                }
            }

            if (!edited) {
                val messageId = tdClient.uploadFile(
                    localPath = file.absolutePath,
                    chatId = chatId,
                    caption = caption,
                    sizeBytes = file.length()
                )
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
     * Looks for a previous manifest in Saved Messages and, if found, restores every entry as an
     * already-UPLOADED row plus the saved destination settings — so a reinstall picks up right
     * where the old install left off instead of treating every file as new. [force] restores
     * even if the local DB already has rows (used by the "Restore backup data" button in
     * Settings, as opposed to the automatic once-per-install check).
     */
    suspend fun restoreIfAvailable(force: Boolean = false): RestoreResult = withContext(Dispatchers.IO) {
        try {
            if (!force && dao.totalRowCount() > 0) return@withContext RestoreResult.NothingToDo
            if (!tdClient.awaitReady(30_000)) return@withContext RestoreResult.NotSignedIn

            val message = tdClient.findLatestOwnDocument(MANIFEST_MARKER)
                ?: return@withContext RestoreResult.NoManifestFound

            settings.setManifestLocation(message.chatId, message.id)

            val downloaded = tdClient.downloadFile(message)
            val json = readGzipped(File(downloaded.path))
            val manifest = BackupManifest.parse(json)

            val rows = manifest.entries.map { it.toUploadedRecord() }
            var inserted = 0
            rows.chunked(200).forEach { chunk ->
                inserted += dao.insertRestored(chunk).count { it != -1L }
            }

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
        GZIPOutputStream(file.outputStream()).use { gz ->
            gz.write(json.toString().toByteArray(Charsets.UTF_8))
        }
        return file
    }

    private fun readGzipped(file: File): JSONObject {
        val text = GZIPInputStream(file.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
        return JSONObject(text)
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

        fun get(context: Context): ManifestSync =
            instance ?: synchronized(this) {
                instance ?: ManifestSync(context.applicationContext).also { instance = it }
            }
    }
}
