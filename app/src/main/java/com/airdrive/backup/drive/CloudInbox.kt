package com.airdrive.backup.drive

import android.content.Context
import android.net.Uri
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.LocalState
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.scanner.Categorizer
import com.airdrive.backup.util.Fingerprint
import com.airdrive.backup.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Landing area for files copied INTO the AirDrive drive from the Files app or another app.
 *
 * Each incoming file is staged in app-private storage, registered in the normal file index as a
 * pending upload, and then the existing backup queue uploads it like any other file. Once it is
 * safely in Telegram the staged copy is deleted, so copying into the drive does not use space.
 * Rows are registered with LocalState.UNKNOWN (like rows rebuilt from the manifest): they have no
 * "phone location" for the scanner, cleanup assistant or deleted-files screen to reason about.
 */
object CloudInbox {
    private const val DIR = "cloud-inbox"
    private val TOKEN = Regex("[0-9a-fA-F-]{36}")

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    /** `file://` prefix shared by every staged file's uri in the index. */
    fun uriPrefix(ctx: Context): String = Uri.fromFile(dir(ctx)).toString().trimEnd('/') + "/"

    /** Reserves an empty staged file and returns its document id ("in:<token>") and the file. */
    fun newSlot(ctx: Context, displayName: String): Pair<String, File> {
        val token = UUID.randomUUID().toString()
        val folder = File(dir(ctx), token).apply { mkdirs() }
        val file = File(folder, sanitize(displayName))
        file.createNewFile()
        return "in:$token" to file
    }

    fun fileFor(ctx: Context, docId: String): File? {
        val token = docId.removePrefix("in:")
        if (!TOKEN.matches(token)) return null
        return File(dir(ctx), token).listFiles()?.firstOrNull { it.isFile }
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.')
        return cleaned.take(120).ifBlank { "file" }
    }

    fun discard(file: File) {
        runCatching { file.delete() }
        runCatching { file.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete() }
    }

    /** Registers a finished write as a pending upload and kicks the normal backup queue. */
    suspend fun commit(ctx: Context, file: File) = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() <= 0L) { discard(file); return@withContext }
        val dao = AppDatabase.get(ctx).fileRecordDao()
        val size = file.length()
        val fingerprint = Fingerprint.compute(file, size)

        // Same content already known (uploaded or queued): nothing to upload twice.
        val dup = dao.findByFingerprint(fingerprint)
        if (dup != null && dup.status != UploadStatus.FAILED && dup.status != UploadStatus.CANCELLED) {
            discard(file)
            return@withContext
        }
        val uri = Uri.fromFile(file).toString()
        if (dao.findByUri(uri) != null) return@withContext

        val name = file.name
        dao.insert(
            FileRecord(
                uri = uri,
                displayName = name,
                sizeBytes = size,
                modifiedAtMillis = file.lastModified(),
                category = Categorizer.categorize(name.lowercase(), ""),
                fingerprint = fingerprint,
                status = UploadStatus.PENDING,
                localState = LocalState.UNKNOWN
            )
        )
        WorkScheduler.runNow(ctx)
    }

    /** Removes a staged file the user deleted before it uploaded, and its pending index row. */
    suspend fun discardWithRecord(ctx: Context, file: File) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(ctx).fileRecordDao()
        dao.findByUri(Uri.fromFile(file).toString())
            ?.takeIf { it.status != UploadStatus.UPLOADED }
            ?.let { dao.deleteById(it.id) }
        discard(file)
    }

    /** Deletes staged copies that are safely in Telegram now, plus abandoned empty slots. */
    suspend fun sweep(ctx: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val dao = AppDatabase.get(ctx).fileRecordDao()
            dao.cloudInboxUploaded(uriPrefix(ctx)).forEach { record ->
                Uri.parse(record.uri).path?.let { discard(File(it)) }
            }
            val cutoff = System.currentTimeMillis() - 60L * 60L * 1000L
            dir(ctx).listFiles()?.forEach { folder ->
                val files = folder.listFiles().orEmpty()
                if (folder.isDirectory && folder.lastModified() < cutoff && files.all { it.length() == 0L }) {
                    folder.deleteRecursively()
                }
            }
        }
    }
}
