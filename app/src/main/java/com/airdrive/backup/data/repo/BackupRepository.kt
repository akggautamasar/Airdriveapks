package com.airdrive.backup.data.repo

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.text.format.DateFormat
import android.util.Log
import com.airdrive.backup.data.backup.ManifestSync
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.data.prefs.DestinationConfig
import com.airdrive.backup.data.prefs.DestinationMode
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.prefs.UploadOrder
import com.airdrive.backup.scanner.FileScanner
import com.airdrive.backup.scanner.ScanProgress
import com.airdrive.backup.telegram.ChannelCheck
import com.airdrive.backup.telegram.ResolvedChat
import com.airdrive.backup.telegram.TdClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
import java.util.concurrent.ConcurrentHashMap

enum class BackupPhase { IDLE, SCANNING, UPLOADING, FINISHED }

data class UploadProgress(
    val phase: BackupPhase = BackupPhase.IDLE,
    val totalFiles: Int = 0,
    val doneFiles: Int = 0,
    val failedFiles: Int = 0,
    val currentFileId: Long? = null,
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

    /**
     * 0f..1f for the progress bar. Fractional on purpose: with one 900MB file in the queue the
     * integer percent below only moves nine times, so the bar looked frozen.
     */
    val fraction: Float
        get() = if (totalBytesQueued <= 0L) 0f
        else (effectiveBytes.toDouble() / totalBytesQueued.toDouble()).toFloat().coerceIn(0f, 1f)

    val percent: Int
        get() = if (totalBytesQueued <= 0L) 0
        else ((effectiveBytes * 100) / totalBytesQueued).toInt().coerceIn(0, 100)

    val etaSeconds: Long
        get() = if (bytesPerSecond <= 0L) 0L
        else (totalBytesQueued - effectiveBytes).coerceAtLeast(0L) / bytesPerSecond
}

/** State of a single restore (Telegram → Downloads/AirDrive), shown by the Restore screen. */
data class RestoreState(
    val fileName: String,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val finishedPath: String? = null,
    val error: String? = null
) {
    val running: Boolean get() = finishedPath == null && error == null

    val fraction: Float
        get() = if (totalBytes <= 0L) 0f
        else (doneBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
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
    private val manifestSync = ManifestSync.get(context)

    private val _progress = MutableStateFlow(UploadProgress())
    val progress: StateFlow<UploadProgress> = _progress

    private val _lastScan = MutableStateFlow<ScanProgress?>(null)
    val lastScan: StateFlow<ScanProgress?> = _lastScan

    private val _restoreState = MutableStateFlow<RestoreState?>(null)
    val restoreState: StateFlow<RestoreState?> = _restoreState

    /** True while the queue is paused: [runBackupQueue] parks between files until this flips back. */
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    /** The currently in-flight upload for each record id, so [cancelUpload] can interrupt just one file. */
    private val activeUploads = ConcurrentHashMap<Long, Deferred<Boolean>>()

    /** Only one queue run at a time, no matter how many times BACK UP NOW is tapped. */
    private val queueMutex = Mutex()

    @Volatile private var activeUploadPath: String? = null
    @Volatile private var speedSampleAt = 0L
    @Volatile private var speedSampleBytes = 0L

    init {
        tdClient.onUploadProgress = { path, uploaded, total -> onFileProgress(path, uploaded, total) }
        // Only one restore runs at a time, so the file id can be ignored — whatever TDLib is
        // downloading is the file the Restore screen is waiting for.
        tdClient.onDownloadProgress = { _, done, total ->
            _restoreState.value?.let { current ->
                if (current.running) {
                    _restoreState.value = current.copy(
                        doneBytes = done,
                        totalBytes = if (total > 0L) total else current.totalBytes
                    )
                }
            }
        }
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
            statusText = when {
                result.accessBlocked -> "Grant “All files access” to back up every folder"
                result.filesExcluded > 0 ->
                    "Found ${result.filesQueued} new file(s) • ${result.filesExcluded} skipped by your rules"
                else -> "Found ${result.filesQueued} new file(s)"
            }
        )
        Log.i(tag, "scan finished: scanned=${result.filesScanned} queued=${result.filesQueued} " +
            "excluded=${result.filesExcluded} wholeDevice=${result.wholeDevice} " +
            "blocked=${result.accessBlocked}")
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

                val dest = settings.destination.first()
                if (dest.needsSetup) {
                    _progress.value = UploadProgress(
                        phase = BackupPhase.FINISHED,
                        isRunning = false,
                        statusText = "Choose where backups should go first"
                    )
                    return@withLock
                }

                // Give earlier failures another go before the run — a flaky network or an
                // expired session strands files that are otherwise perfectly uploadable. Bounded
                // by retryCount so a genuinely broken file is not retried forever.
                if (settings.autoRetryFailed.first()) {
                    val requeued = dao.retryFailedUnder(MAX_AUTO_RETRIES)
                    if (requeued > 0) Log.i(tag, "auto-retrying $requeued previously failed file(s)")
                }

                val order = settings.uploadOrder.first()
                val template = settings.captionTemplate.first()
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
                    // Pause parks right here, between files — whatever is already in flight for
                    // the current file keeps running (it is not cancelled by pausing), but the
                    // next file will not start until resumed.
                    while (_paused.value && currentCoroutineContext().isActive) {
                        delay(400)
                    }
                    if (!currentCoroutineContext().isActive) break

                    val batch = nextBatch(order).filter { attempted.add(it.id) }
                    if (batch.isEmpty()) break

                    for (record in batch) {
                        if (!currentCoroutineContext().isActive) break
                        while (_paused.value && currentCoroutineContext().isActive) {
                            delay(400)
                        }
                        if (!currentCoroutineContext().isActive) break

                        // Cancelled between being batched and now (e.g. the user cancelled a
                        // whole page of pending files while an earlier one was still uploading).
                        val fresh = dao.findByUri(record.uri)
                        if (fresh == null || fresh.status != UploadStatus.PENDING) continue

                        val ok = coroutineScope {
                            val deferred = async { uploadOne(record, dest, template) }
                            activeUploads[record.id] = deferred
                            try {
                                deferred.await()
                            } catch (e: CancellationException) {
                                false
                            } finally {
                                activeUploads.remove(record.id)
                            }
                        }
                        if (ok) bytesDone += record.sizeBytes else failed++
                        done++
                        _progress.value = _progress.value.copy(
                            doneFiles = done,
                            failedFiles = failed,
                            bytesUploaded = bytesDone,
                            currentFileUploadedBytes = 0
                        )
                        onEachDone(record, ok)

                        // Checkpointed periodically rather than after every file: a manifest
                        // upload is itself a Telegram round trip, and doing it 2500 times in a
                        // row would roughly double the total run time for no real benefit.
                        if (done % MANIFEST_SYNC_EVERY == 0) {
                            runCatching { manifestSync.sync() }
                        }
                    }
                }

                runCatching { manifestSync.sync() }

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

    /** Drains whichever end of the queue the user asked for. */
    private suspend fun nextBatch(order: UploadOrder): List<FileRecord> = when (order) {
        UploadOrder.OLDEST_FIRST -> dao.nextPendingBatch(BATCH_SIZE)
        UploadOrder.NEWEST_FIRST -> dao.nextPendingNewest(BATCH_SIZE)
        UploadOrder.SMALLEST_FIRST -> dao.nextPendingSmallest(BATCH_SIZE)
    }

    /**
     * Where this file goes. Read from settings on every run rather than from the row, so changing
     * the destination also moves files that are already queued. Saved Messages is resolved through
     * TDLib and cached there for the rest of the session.
     */
    private suspend fun resolveDestination(record: FileRecord, dest: DestinationConfig): Long =
        when (dest.mode) {
            DestinationMode.SAVED_MESSAGES -> tdClient.savedMessagesChatId()
            DestinationMode.SINGLE_CHAT -> dest.singleChatId
            DestinationMode.PER_CATEGORY ->
                dest.perCategory[record.category]?.takeIf { it != 0L } ?: record.destinationChannelId
        }

    private suspend fun uploadOne(
        record: FileRecord,
        dest: DestinationConfig,
        template: String
    ): Boolean {
        _progress.value = _progress.value.copy(
            currentFileId = record.id,
            currentFileName = record.displayName,
            currentFileBytes = record.sizeBytes,
            currentFileUploadedBytes = 0,
            statusText = null
        )
        dao.markStatus(record.id, UploadStatus.UPLOADING)

        var source: UploadSource? = null
        return try {
            val chatId = resolveDestination(record, dest)
            source = resolveSource(record)
            activeUploadPath = source.path
            resetSpeedSample()
            val messageId = tdClient.uploadFile(
                localPath = source.path,
                chatId = chatId,
                caption = applyTemplate(template, record),
                sizeBytes = record.sizeBytes
            )
            // The chat is recorded alongside the message: restore needs the pair, and the chat
            // that actually received the file is not necessarily the category's channel any more.
            dao.markUploaded(record.id, messageId, chatId, System.currentTimeMillis())
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

    /**
     * Fills the user's caption template. Supported placeholders: {name} {date} {size} {folder}
     * {path} {category} {ext}. A line whose placeholders all came back empty is dropped, so a
     * template can carry decoration like "📁 {folder}" without leaving a bare emoji behind for
     * files whose folder is unknown.
     */
    private fun applyTemplate(template: String, record: FileRecord): String {
        val path = Uri.parse(record.uri).path.orEmpty()
        val folder = path.substringBeforeLast('/', "").removePrefix(PRIMARY_STORAGE)
        val date = DateFormat.format("dd-MM-yyyy HH:mm", Date(record.modifiedAtMillis)).toString()
        val filled = template
            .replace("{name}", record.displayName)
            .replace("{date}", date)
            .replace("{size}", formatSize(record.sizeBytes))
            .replace("{folder}", folder)
            .replace("{path}", path)
            .replace("{category}", record.category.name.lowercase().replace('_', ' '))
            .replace("{ext}", record.displayName.substringAfterLast('.', ""))
        return filled.lines()
            .filter { line -> line.isEmpty() || line.any { it.isLetterOrDigit() } }
            .joinToString("\n")
            .trim()
            .take(1024)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
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

    /** Probes whichever destination is actually configured, whatever the mode. */
    suspend fun testDestination(): ChannelCheck {
        if (!tdClient.awaitReady(20_000)) return ChannelCheck.Failed("Not signed in to Telegram yet")
        val dest = settings.destination.first()
        return when (dest.mode) {
            DestinationMode.SAVED_MESSAGES -> runCatching { tdClient.savedMessagesChatId() }.fold(
                onSuccess = { ChannelCheck.Ok("Saved Messages") },
                onFailure = { ChannelCheck.Failed(it.message ?: it.javaClass.simpleName) }
            )
            DestinationMode.SINGLE_CHAT -> {
                tdClient.ensureChatListLoaded()
                tdClient.checkChannel(dest.singleChatId)
            }
            DestinationMode.PER_CATEGORY -> {
                val results = testAllChannels()
                val ok = results.count { it.second is ChannelCheck.Ok }
                if (ok == results.size) {
                    ChannelCheck.Ok("All ${results.size} channels reachable")
                } else {
                    val firstProblem = results.firstOrNull { it.second is ChannelCheck.Failed }
                    ChannelCheck.Failed(
                        "$ok of ${results.size} channels reachable" +
                            (firstProblem?.let { " — ${it.first.name}: ${(it.second as ChannelCheck.Failed).reason}" } ?: "")
                    )
                }
            }
        }
    }

    /** Turns a pasted ID / @username / t.me link into a real chat, signing in first if needed. */
    suspend fun resolveChatInput(raw: String): ResolvedChat {
        if (!tdClient.awaitReady(20_000)) throw IllegalStateException("Not signed in to Telegram yet")
        tdClient.ensureChatListLoaded()
        return tdClient.resolveChatInput(raw)
    }

    /** Creates a private channel for the signed-in account and returns it. */
    suspend fun createChannel(title: String): ResolvedChat {
        if (!tdClient.awaitReady(20_000)) throw IllegalStateException("Not signed in to Telegram yet")
        return tdClient.createChannel(title)
    }

    /** Points already-queued rows of [category] at a newly saved channel id. */
    suspend fun repointCategory(category: BackupCategory, channelId: Long) =
        dao.repointCategory(category, channelId)

    suspend fun pendingSummary(): Pair<Int, Long> = dao.pendingCount() to dao.pendingBytes()

    suspend fun retryAllFailed() = dao.retryAllFailed()

    suspend fun retryOne(id: Long) = dao.retryOne(id)

    /** Toggled by the Pause/Resume button; the running queue checks this between files. */
    fun setPaused(v: Boolean) {
        _paused.value = v
    }

    /**
     * Cancels one file. A file still waiting in the queue is simply marked CANCELLED and drops
     * out of every PENDING query. A file actively uploading has its upload job interrupted —
     * TDLib itself may finish streaming the bytes it already had in flight, but AirDrive stops
     * waiting for it and the record goes back to CANCELLED rather than UPLOADED.
     */
    suspend fun cancelUpload(id: Long) {
        activeUploads[id]?.cancel()
        dao.markCancelled(id)
    }

    /** Cancels every file still waiting (not yet started); files already uploading finish normally. */
    suspend fun cancelAllPending(): Int = dao.cancelAllPending()

    suspend fun requeueCancelled(id: Long) = dao.requeueCancelled(id)

    fun cancelledFilesFlow(limit: Int = 500) = dao.cancelledFilesFlow()

    // ------------------------------------------------------------------ manifest (Telegram-backed state)

    /**
     * Checks Saved Messages for a manifest from a previous install and restores it, but only if
     * the local DB is empty — i.e. this really is a fresh install, not a normal running app.
     * Safe to call every time the app starts; it is a no-op after the first successful check.
     */
    suspend fun restoreManifestIfFreshInstall() = manifestSync.restoreIfAvailable(force = false)

    /** Explicit "Restore backup data" button in Settings — restores even with existing local rows. */
    suspend fun restoreManifestForced() = manifestSync.restoreIfAvailable(force = true)

    /** Explicit "Sync backup data now" button in Settings. */
    suspend fun syncManifestNow(): Boolean = manifestSync.sync()

    // ------------------------------------------------------------------ restore

    /**
     * Pulls [record] back out of Telegram into Downloads/AirDrive/. TDLib downloads into its own
     * cache, so the bytes are copied out rather than moved — moving would leave TDLib's file
     * database pointing at nothing. Progress is published on [restoreState].
     */
    suspend fun restoreFile(record: FileRecord): File = withContext(Dispatchers.IO) {
        val messageId = record.telegramMessageId
            ?: throw IllegalStateException("No Telegram message was recorded for this file")
        val chatId = record.destinationChannelId.takeIf { it != 0L }
            ?: throw IllegalStateException("No Telegram chat was recorded for this file")

        _restoreState.value = RestoreState(record.displayName, totalBytes = record.sizeBytes)
        try {
            if (!tdClient.awaitReady(30_000)) throw IllegalStateException("Not signed in to Telegram")
            val fetched = tdClient.downloadMessageFile(chatId, messageId)
            val target = uniqueFile(restoreDir(), record.displayName.ifBlank { fetched.fileName })
            File(fetched.path).inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
            }
            _restoreState.value = RestoreState(
                fileName = record.displayName,
                doneBytes = target.length(),
                totalBytes = target.length(),
                finishedPath = target.absolutePath
            )
            Log.i(tag, "restored ${record.displayName} to ${target.absolutePath}")
            target
        } catch (e: CancellationException) {
            _restoreState.value = null
            throw e
        } catch (e: Exception) {
            _restoreState.value = RestoreState(
                fileName = record.displayName,
                error = e.message?.take(300) ?: e.javaClass.simpleName
            )
            throw e
        }
    }

    fun clearRestoreState() {
        _restoreState.value = null
    }

    fun restorableFlow(query: String, limit: Int = 200) = dao.restorableFlow(query, limit)

    // ------------------------------------------------------------------ export

    /**
     * Writes a CSV of everything uploaded, so the backup is still findable if the app or the phone
     * is gone: each row carries the chat and message id the file lives in.
     */
    suspend fun exportManifest(): File = withContext(Dispatchers.IO) {
        val target = uniqueFile(restoreDir(), "airdrive-manifest.csv")
        target.bufferedWriter().use { out ->
            out.appendLine("name,category,size_bytes,uploaded_at,chat_id,message_id,source_path")
            var offset = 0
            while (true) {
                val page = dao.uploadedPage(EXPORT_PAGE, offset)
                if (page.isEmpty()) break
                for (r in page) {
                    out.appendLine(
                        listOf(
                            r.displayName,
                            r.category.name,
                            r.sizeBytes.toString(),
                            r.uploadedAtMillis?.let { DateFormat.format("yyyy-MM-dd HH:mm", Date(it)).toString() } ?: "",
                            r.destinationChannelId.toString(),
                            r.telegramMessageId?.toString() ?: "",
                            Uri.parse(r.uri).path.orEmpty()
                        ).joinToString(",") { csvCell(it) }
                    )
                }
                offset += page.size
            }
        }
        Log.i(tag, "manifest written to ${target.absolutePath}")
        target
    }

    /** Plain-text copy of the settings, for moving to another phone. */
    suspend fun exportSettings(): File = withContext(Dispatchers.IO) {
        val target = uniqueFile(restoreDir(), "airdrive-settings.txt")
        target.writeText(settings.exportSummary())
        target
    }

    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"").replace("\n", " ") + "\""
        } else {
            value
        }

    /** Downloads/AirDrive — a normal folder the user can open in any file manager. */
    @Suppress("DEPRECATION")
    private fun restoreDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AirDrive"
        ).apply { mkdirs() }

    /** Never overwrites: "report.pdf" becomes "report (1).pdf" if it is already there. */
    private fun uniqueFile(dir: File, name: String): File {
        val safe = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "restored" }
        val base = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "")
        var candidate = File(dir, safe)
        var n = 1
        while (candidate.exists() && n < 1000) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = File(dir, "$base ($n)$suffix")
            n++
        }
        return candidate
    }

    companion object {
        private const val BATCH_SIZE = 50
        private const val EXPORT_PAGE = 500

        /** How many times a file may fail before automatic retry stops picking it up. */
        private const val MAX_AUTO_RETRIES = 5

        /** Checkpoint the Telegram-backed manifest every this-many successful/failed files. */
        private const val MANIFEST_SYNC_EVERY = 100

        private const val PRIMARY_STORAGE = "/storage/emulated/0"

        @Volatile private var instance: BackupRepository? = null

        fun get(context: Context): BackupRepository =
            instance ?: synchronized(this) {
                instance ?: BackupRepository(context.applicationContext).also { instance = it }
            }
    }
}
