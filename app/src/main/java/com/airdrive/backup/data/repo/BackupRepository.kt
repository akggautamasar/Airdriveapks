package com.airdrive.backup.data.repo

import android.content.Context
import android.net.Uri
import android.text.format.DateFormat
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.scanner.FileScanner
import com.airdrive.backup.telegram.TdClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Date
import java.util.Locale

data class UploadProgress(
    val totalFiles: Int,
    val doneFiles: Int,
    val currentFileName: String?,
    val currentFileBytes: Long,
    val totalBytesQueued: Long,
    val bytesUploaded: Long,
    val isRunning: Boolean
)

class BackupRepository(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val scanner = FileScanner(context)
    private val tdClient = TdClient.get(context)

    private val _progress = MutableStateFlow(
        UploadProgress(0, 0, null, 0, 0, 0, isRunning = false)
    )
    val progress: StateFlow<UploadProgress> = _progress

    suspend fun scan() = scanner.scanAll { p ->
        _progress.value = _progress.value.copy(currentFileName = "Scanning: ${p.currentDir}")
    }

    /**
     * Uploads every PENDING file sequentially (deliberately not parallel, to stay well
     * under Telegram's per-account rate limits). Returns when the queue is drained or the
     * coroutine is cancelled (e.g. the foreground service is stopped by the user pausing).
     */
    suspend fun runBackupQueue(onEachDone: suspend (FileRecord, Boolean) -> Unit = { _, _ -> }) {
        val pending = db.fileRecordDao().pendingFiles()
        val totalBytes = pending.sumOf { it.sizeBytes }
        var bytesDone = 0L
        _progress.value = UploadProgress(pending.size, 0, null, 0, totalBytes, 0, isRunning = true)

        for ((index, record) in pending.withIndex()) {
            _progress.value = _progress.value.copy(
                currentFileName = record.displayName,
                currentFileBytes = record.sizeBytes
            )
            db.fileRecordDao().markStatus(record.id, UploadStatus.UPLOADING)

            var stagedFile: File? = null
            val success = try {
                stagedFile = stageToCache(record)
                val caption = buildCaption(record)
                val messageId = tdClient.uploadFile(stagedFile.absolutePath, record.destinationChannelId, caption)
                db.fileRecordDao().markUploaded(record.id, messageId, System.currentTimeMillis())
                true
            } catch (e: Exception) {
                db.fileRecordDao().markFailed(record.id, e.message ?: e.javaClass.simpleName)
                false
            } finally {
                stagedFile?.delete()
            }

            bytesDone += record.sizeBytes
            _progress.value = _progress.value.copy(doneFiles = index + 1, bytesUploaded = bytesDone)
            onEachDone(record, success)
        }

        _progress.value = _progress.value.copy(isRunning = false, currentFileName = null)
    }

    /** Copies a SAF document into app cache so TDLib (which needs a real filesystem path) can read it. */
    private fun stageToCache(record: FileRecord): File {
        val dir = File(context.cacheDir, "upload_staging").apply { mkdirs() }
        val target = File(dir, "${record.id}_${sanitize(record.displayName)}")
        context.contentResolver.openInputStream(Uri.parse(record.uri))?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
        } ?: throw IllegalStateException("Cannot open ${record.uri}; file may have been deleted or permission revoked")
        return target
    }

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun buildCaption(record: FileRecord): String {
        val date = DateFormat.format("dd-MM-yyyy HH:mm", Date(record.modifiedAtMillis))
        val sizeMb = String.format(Locale.US, "%.2f", record.sizeBytes / 1024.0 / 1024.0)
        return "\uD83D\uDCC4 ${record.displayName}\n\uD83D\uDCC5 $date\n\uD83D\uDCBE ${sizeMb}MB"
    }

    suspend fun retryAllFailed() = db.fileRecordDao().retryAllFailed()
    suspend fun retryOne(id: Long) = db.fileRecordDao().retryOne(id)
}
