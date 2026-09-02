package com.airdrive.backup.data.repo

import android.content.Context
import android.net.Uri
import android.text.format.DateFormat
import android.util.Log
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.scanner.FileScanner
import com.airdrive.backup.scanner.ScanProgress
import com.airdrive.backup.telegram.ChannelCheck
import com.airdrive.backup.telegram.TdClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Locale

enum class BackupPhase { IDLE, SCANNING, UPLOADING, FINISHED }

data class UploadProgress(
    val phase: BackupPhase = BackupPhase.IDLE,
    val totalFiles: Int = 0,
    val doneFiles: Int = 0,
    val failedFiles: Int = 0,
    val currentFileName: String? = null,
    val currentFileBytes: Long = 0,
    val currentFileUploadedBytes: Long = 0,
    val totalBytesQueued: Long = 0,
    val bytesUploaded: Long = 0,
    val bytesPerSecond: Long = 0,
    val statusText: String? = null,
    val isRunning: Boolean = false
) {
    /** Completed bytes plus however much of the in-flight file Telegram has taken. */
    val effectiveBytes: Long get() = bytesUploaded + currentFileUploadedBytes

    val percent: Int
        get() = if (totalBytesQueued <= 0L) 0
        else ((effectiveBytes * 100) / totalBytesQueued).toInt().coerceIn(0, 100)

    val etaSeconds: Long
        get() = if (bytesPerSecond <= 0L) 0L
        else (totalBytesQueued - effectiveBytes).coerceAtLeast(0L) / bytesPerSecond
}

/**
 * Owns the backup queue. Deliberately a process singleton: the worker and the UI must share one
 * instance, otherwise the screen collects a StateFlow nobody writes to — which is why the
 * progress screen used to sit at "0 / 0 files" while uploads were actually running.
 */
class BackupRepository private constructor(private val context: Context) {

    private val tag = "AirDrive.Repo"
    private val dao = AppDatabase.get(context).fileRecordDao()
    private val scanner = FileScanner(context)
    private val settings = SettingsStore(context)
    private val tdClient = TdClient.get(context)

    private val _progress = MutableStateFlow(UploadProgress())
    val progress: StateFlow<UploadProgress> = _progress

    private val _lastScan = MutableStateFlow<ScanProgress?>(null)
    val lastScan: StateFlow<ScanProgress?> = _lastScan

    /** Only one queue run at a time, no matter how many times BACK UP NOW is tapped. */
    private val queueMutex = Mutex()

    @Volatile private var activeUploadPath: String? = null
    @Volatile private var speedSampleAt = 0L
    @Volatile private var speedSampleBytes = 0L

    init {
        tdClient.onUploadProgress = { path, uploaded, total -> onFileProgress(path, uploaded, total) }
    }

    private fun onFileProgress(path: String, uploaded: Long, total: Long) {
        if (path != activeUploadPath) return
        val snapshot = _progress.value
        val capped = if (total > 0L) uploaded.coerceAtMost(total) else uploaded

        var speed = snapshot.bytesPerSecond
        val now = System.currentTimeMillis()
        val elapsed = now - speedSampleAt
        if (elapsed >= 1000L) {
            val delta = capped - speedSampleBytes
            if (delta >= 0L) {
                val instant = delta * 1000L / elapsed
                // Light smoothing so the number does not jump around every second.
                speed = if (speed <= 0L) instant else (speed * 2L + instant) / 3L
            }
            speedSampleAt = now
            speedSampleBytes = capped
        }

        _progress.value = snapshot.copy(currentFileUploadedBytes = capped, bytesPerSecond = speed)
    }

    private fun resetSpeedSample() {
        speedSampleAt = System.currentTimeMillis()
        speedSampleBytes = 0L
    }

    /**
     * Walks storage and queues anything new. Reports live progress so the UI is never blank.
     *
     * Runs on Dispatchers.IO on purpose: a whole-device walk plus fingerprint hashing is far too
     * much work for whichever thread the caller happens to be on, and the Ready screen calls this
     * straight from a LaunchedEffect (i.e. the main thread).
     */
    suspend fun scan(): ScanProgress = withContext(Dispatchers.IO) {
        _progress.value = _progress.value.copy(
            phase = BackupPhase.SCANNING,
            isRunning = true,
            statusText = "Scanning storage…"
        )
        val result = scanner.scanAll { p ->
            _progress.value = _progress.value.copy(
                phase = BackupPhase.SCANNING,
                isRunning = true,
                statusText = "Scanned ${p.filesScanned} • queued ${p.filesQueued}",
                currentFileName = p.currentDir.takeLast(52).ifBlank { null }
            )
        }
        _lastScan.value = result
        _progress.value = _progress.value.copy(
            statusText = if (result.accessBlocked) {
                "Grant “All files access” to back up every folder"
            } else {
                "Found ${result.filesQueued} new file(s)"
            }
        )
        Log.i(tag, "scan finished: scanned=${result.filesScanned} queued=${result.filesQueued} " +
            "wholeDevice=${result.wholeDevice} blocked=${result.accessBlocked}")
        result
    }

    /**
     * Uploads every PENDING file one at a time (deliberately not parallel, to stay well under
     * Telegram's per-account rate limits) and only counts a file as done once Telegram has
     * confirmed it. Returns when the queue is drained or the coroutine is cancelled — a
     * cancelled file goes back to PENDING rather than being lost.
     */
    suspend fun runBackupQueue(onEachDone: suspend (FileRecord, Boolean) -> Unit = { _, _ -> }) {
        withContext(Dispatchers.IO) {
            queueMutex.withLock {
                // Anything left UPLOADING is from a run that was killed; it would otherwise be
                // invisible to every query forever.
                val revived = dao.resetInFlight()
                if (revived > 0) Log.i(tag, "reset $revived stuck UPLOADING record(s)")

                val channels = settings.allChannels.first().perCategory
                val total = dao.pendingCount()
                val totalBytes = dao.pendingBytes()

                _progress.value = UploadProgress(
                    phase = BackupPhase.UPLOADING,
                    totalFiles = total,
                    totalBytesQueued = totalBytes,
                    isRunning = true,
                    statusText = if (total == 0) "Nothing to back up" else null
                )

                var done = 0
                var failed = 0
                var bytesDone = 0L
                val attempted = HashSet<Long>()

                while (currentCoroutineContext().isActive) {
                    val batch = dao.nextPendingBatch(BATCH_SIZE).filter { attempted.add(it.id) }
                    if (batch.isEmpty()) break

                    for (record in batch) {
                        if (!currentCoroutineContext().isActive) break
                        val ok = uploadOne(record, channels)
                        if (ok) bytesDone += record.sizeBytes else failed++
                        done++
                        _progress.value = _progress.value.copy(
                            doneFiles = done,
                            failedFiles = failed,
                            bytesUploaded = bytesDone,
                            currentFileUploadedBytes = 0
                        )
                        onEachDone(record, ok)
                    }
                }

                _progress.value = _progress.value.copy(
                    phase = BackupPhase.FINISHED,
                    isRunning = false,
                    currentFileName = null,
                    currentFileUploadedBytes = 0,
                    bytesPerSecond = 0,
                    statusText = if (failed > 0) "$done done, $failed failed" else "Backup complete"
                )
            }
        }
    }

    private suspend fun uploadOne(record: FileRecord, channels: Map<BackupCategory, Long>): Boolean {
        // Read the channel from settings, not from the row: the row was written when the file was
        // first seen, so edits in Channel Configuration would otherwise never reach queued files.
        val channelId = channels[record.category] ?: record.destinationChannelId

        _progress.value = _progress.value.copy(
            currentFileName = record.displayName,
            currentFileBytes = record.sizeBytes,
            currentFileUploadedBytes = 0,
            statusText = null
        )
        dao.markStatus(record.id, UploadStatus.UPLOADING)

        var source: UploadSource? = null
        return try {
            source = resolveSource(record)
            activeUploadPath = source.path
            resetSpeedSample()
            val messageId = tdClient.uploadFile(
                localPath = source.path,
                chatId = channelId,
                caption = buildCaption(record),
                sizeBytes = record.sizeBytes
            )
            dao.markUploaded(record.id, messageId, System.currentTimeMillis())
            true
        } catch (e: CancellationException) {
            // Paused or the worker was stopped: put the file back so the next run picks it up.
            dao.markStatus(record.id, UploadStatus.PENDING)
            throw e
        } catch (e: Exception) {
            val reason = e.message?.take(400) ?: e.javaClass.simpleName
            Log.w(tag, "upload failed for ${record.displayName}: $reason")
            dao.markFailed(record.id, reason)
            false
        } finally {
            activeUploadPath = null
            val staged = source
            if (staged != null && staged.temporary) {
                runCatching { File(staged.path).delete() }
            }
        }
    }

    private data class UploadSource(val path: String, val temporary: Boolean)

    /**
     * Where TDLib should read the bytes from. Files discovered by the whole-device scanner are
     * uploaded straight from their real path, so nothing is copied and nothing is ever deleted —
     * only a temporary staging copy (SAF documents, which have no usable path) is cleaned up.
     */
    private fun resolveSource(record: FileRecord): UploadSource {
        val uri = Uri.parse(record.uri)
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: throw IllegalStateException("Malformed file URI: ${record.uri}")
            val file = File(path)
            if (!file.isFile) throw IllegalStateException("File no longer exists: $path")
            if (!file.canRead()) {
                throw IllegalStateException("Cannot read $path — “All files access” may have been revoked")
            }
            return UploadSource(file.absolutePath, temporary = false)
        }
        return UploadSource(stageToCache(record).absolutePath, temporary = true)
    }

    /** Copies a SAF document into app cache so TDLib (which needs a real path) can read it. */
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
        val folder = Uri.parse(record.uri).path
            ?.substringBeforeLast('/', "")
            ?.removePrefix("/storage/emulated/0")
            ?.takeIf { it.isNotBlank() }
        val where = if (folder != null) "\n📁 $folder" else ""
        return "📄 ${record.displayName}\n📅 $date\n💾 ${sizeMb}MB$where"
    }

    /** Makes sure Telegram is usable before a run, and that the chat list is loaded. */
    suspend fun prepareTelegram(): Boolean {
        if (!tdClient.awaitReady()) return false
        tdClient.ensureChatListLoaded()
        return true
    }

    /** Probes every configured channel; used by the Test channels button. */
    suspend fun testAllChannels(): List<Pair<BackupCategory, ChannelCheck>> {
        if (!tdClient.awaitReady(20_000)) {
            return BackupCategory.values().map { it to ChannelCheck.Failed("Not signed in to Telegram yet") }
        }
        tdClient.ensureChatListLoaded()
        val channels = settings.allChannels.first().perCategory
        return BackupCategory.values().map { category ->
            category to tdClient.checkChannel(channels[category] ?: 0L)
        }
    }

    /** Points already-queued rows of [category] at a newly saved channel id. */
    suspend fun repointCategory(category: BackupCategory, channelId: Long) =
        dao.repointCategory(category, channelId)

    suspend fun pendingSummary(): Pair<Int, Long> = dao.pendingCount() to dao.pendingBytes()

    suspend fun retryAllFailed() = dao.retryAllFailed()

    suspend fun retryOne(id: Long) = dao.retryOne(id)

    companion object {
        private const val BATCH_SIZE = 50

        @Volatile private var instance: BackupRepository? = null

        fun get(context: Context): BackupRepository =
            instance ?: synchronized(this) {
                instance ?: BackupRepository(context.applicationContext).also { instance = it }
            }
    }
}
