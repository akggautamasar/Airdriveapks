package com.airdrive.backup.data.repo

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.text.format.DateFormat
import android.util.Log
import com.airdrive.backup.data.backup.ManifestSync
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.BackupRun
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.FileVersion
import com.airdrive.backup.data.db.LocalState
import com.airdrive.backup.data.db.RunOutcome
import com.airdrive.backup.data.db.RunTrigger
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.data.db.VerifyState
import com.airdrive.backup.data.prefs.DestinationConfig
import com.airdrive.backup.data.prefs.DestinationMode
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.prefs.UploadOrder
import com.airdrive.backup.scanner.FileScanner
import com.airdrive.backup.scanner.ScanProgress
import com.airdrive.backup.telegram.ChannelCheck
import com.airdrive.backup.telegram.RemoteFile
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
 * Progress of a device-to-device migration: whole categories pulled back onto a new phone.
 *
 * Deliberately counted in files rather than bytes. A migration is thousands of small-ish transfers
 * and the total byte weight is not known until the last page of the queue has been read, so
 * "1,204 of 9,850 files" is both cheaper to produce and easier to read than a byte percentage.
 * The inner per-file bar comes from [RestoreState], which the same download path already fills in.
 */
data class MigrationState(
    val running: Boolean = false,
    /**
     * Enqueued, but WorkManager has not started it yet — normally waiting for the kind of network
     * the backup settings allow. [running] is true as well, so the buttons stay disabled, but the
     * card has to say "waiting" instead of implying files are already moving.
     */
    val queued: Boolean = false,
    val filesTotal: Int = 0,
    val filesDone: Int = 0,
    val filesFailed: Int = 0,
    val bytesDone: Long = 0,
    val currentFile: String? = null,
    val currentCategory: BackupCategory? = null,
    val cancelled: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null
) {
    /** Failures count towards the bar: they are done being attempted, so the bar must not stall. */
    val fraction: Float
        get() = if (filesTotal <= 0) 0f
        else ((filesDone + filesFailed).toDouble() / filesTotal.toDouble()).toFloat().coerceIn(0f, 1f)

    /** True before the first migration of the session and after the result has been dismissed. */
    val idle: Boolean get() = !running && !finished && !cancelled && error == null
}

/**
 * What one storage-cleanup pass did. Every skip is counted separately because the reasons need
 * different words on screen: "we could not reach Telegram" is worth retrying, "the copy is not
 * there" queued a re-upload, and "the file would not delete" is a permissions problem.
 */
data class CleanupResult(
    val freedFiles: Int = 0,
    val freedBytes: Long = 0,
    val queuedForRepair: Int = 0,
    val failed: Int = 0,
    val stoppedReason: String? = null
) {
    /** Nothing was deleted and nothing went wrong — the user picked files that were all fine. */
    val nothingHappened: Boolean
        get() = freedFiles == 0 && queuedForRepair == 0 && failed == 0 && stoppedReason == null
}

/**
 * What one verification sweep found (wishlist item 15). [unreachable] is kept apart from [problems]
 * on purpose: a file AirDrive could not ask about is not a file with something wrong with it, and
 * merging the two would cry wolf every time the connection dropped.
 */
data class VerifyResult(
    val checked: Int = 0,
    val confirmed: Int = 0,
    val problems: Int = 0,
    val requeued: Int = 0,
    val unreachable: Int = 0,
    val stoppedReason: String? = null
) {
    /** Nothing was due for checking — the sweep had no work, which is not the same as a clean bill. */
    val nothingToDo: Boolean
        get() = checked == 0 && stoppedReason == null
}

/**
 * Owns the backup queue. Deliberately a process singleton: the worker and the UI must share one
 * instance, otherwise the screen collects a StateFlow nobody writes to — which is why the
 * progress screen used to sit at "0 / 0 files" while uploads were actually running.
 */
class BackupRepository private constructor(private val context: Context) {

    private val tag = "AirDrive.Repo"
    private val dao = AppDatabase.get(context).fileRecordDao()
    private val runDao = AppDatabase.get(context).backupRunDao()
    private val versionDao = AppDatabase.get(context).fileVersionDao()
    private val scanner = FileScanner(context)
    private val settings = SettingsStore(context)
    private val tdClient = TdClient.get(context)
    private val manifestSync = ManifestSync.get(context)

    private val _progress = MutableStateFlow(UploadProgress())
    val progress: StateFlow<UploadProgress> = _progress

    private val _lastScan = MutableStateFlow<ScanProgress?>(null)
    val lastScan: StateFlow<ScanProgress?> = _lastScan

    /**
     * The run row the current backup is being recorded against, or null when there is no run —
     * a plain scan from the dashboard deliberately does not create one, otherwise every visit to
     * the Ready screen would leave an empty entry in the timeline.
     */
    @Volatile private var currentRunId: Long? = null

    private val _restoreState = MutableStateFlow<RestoreState?>(null)
    val restoreState: StateFlow<RestoreState?> = _restoreState

    private val _migration = MutableStateFlow(MigrationState())
    val migration: StateFlow<MigrationState> = _migration

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
                statusText = "Scanned ${p.filesScanned} • ${p.filesUnchanged} already backed up • " +
                    "${p.filesQueued} to upload",
                currentFileName = p.currentDir.takeLast(52).ifBlank { null }
            )
        }
        _lastScan.value = result

        // The run row is the timeline's copy of "what changed"; written here rather than at the
        // end of the upload so an interrupted run still shows what the scan found.
        currentRunId?.let { runId ->
            runCatching {
                runDao.setScanCounts(
                    id = runId,
                    scanned = result.filesScanned,
                    newFiles = result.filesNew,
                    modified = result.filesModified,
                    missing = result.filesMissing,
                    renamed = result.filesRenamed
                )
            }
        }

        _progress.value = _progress.value.copy(statusText = scanSummary(result))
        Log.i(tag, "scan finished: scanned=${result.filesScanned} queued=${result.filesQueued} " +
            "unchanged=${result.filesUnchanged} new=${result.filesNew} modified=${result.filesModified} " +
            "renamed=${result.filesRenamed} missing=${result.filesMissing} " +
            "duplicate=${result.filesDuplicate} excluded=${result.filesExcluded} " +
            "wholeDevice=${result.wholeDevice} blocked=${result.accessBlocked} complete=${result.complete}")
        result
    }

    /**
     * The one line the dashboard shows after a scan. This is the incremental story in words:
     * "10 000 scanned • 9 850 already backed up • 150 to upload" — the whole point of the feature
     * is that the second number is large and the third one is small.
     */
    private fun scanSummary(result: ScanProgress): String {
        if (result.accessBlocked) return "Grant “All files access” to back up every folder"
        if (result.filesScanned == 0) {
            return if (result.wholeDevice) "No files found" else "Add a folder to back up"
        }
        if (result.filesQueued == 0) {
            val extras = buildList {
                if (result.filesRenamed > 0) add("${result.filesRenamed} moved")
                if (result.filesMissing > 0) add("${result.filesMissing} deleted locally")
            }
            val suffix = if (extras.isEmpty()) "" else " • ${extras.joinToString(" • ")}"
            return "Everything is backed up — ${result.filesScanned} file(s) checked$suffix"
        }
        val parts = buildList {
            add("${result.filesScanned} scanned")
            if (result.filesUnchanged > 0) add("${result.filesUnchanged} already backed up")
            add("${result.filesQueued} to upload")
            if (result.filesModified > 0) add("${result.filesModified} changed")
            if (result.filesRenamed > 0) add("${result.filesRenamed} moved")
            if (result.filesDuplicate > 0) add("${result.filesDuplicate} duplicate")
            if (result.filesMissing > 0) add("${result.filesMissing} deleted locally")
            if (result.filesExcluded > 0) add("${result.filesExcluded} skipped by your rules")
        }
        return parts.joinToString(" • ")
    }

    // ------------------------------------------------------------------ runs (the backup timeline)

    /**
     * Opens a run row and returns its id. Everything the run does afterwards — scan counts,
     * per-file uploads, the final outcome — is attributed to it, which is what the timeline reads
     * back. Always paired with [finishRun]; a row left RUNNING is repaired on the next start.
     */
    suspend fun beginRun(
        startedBy: RunTrigger,
        categoryFilter: BackupCategory? = null
    ): Long = withContext(Dispatchers.IO) {
        // A row still marked RUNNING belongs to a process that was killed. Closed here rather than
        // at app start so a run launched by WorkManager while the UI is dead also cleans up.
        runCatching { runDao.closeStaleRuns(System.currentTimeMillis()) }
        val id = runDao.insert(
            BackupRun(
                startedBy = startedBy,
                categoryFilter = categoryFilter?.name.orEmpty(),
                outcome = RunOutcome.RUNNING
            )
        )
        currentRunId = id
        Log.i(tag, "run $id started ($startedBy${categoryFilter?.let { ", ${it.name}" } ?: ""})")
        id
    }

    /**
     * Closes the run. The outcome is derived from what actually happened unless the caller forces
     * one (a blocked run has nothing to derive from): any failure makes the run PARTIAL, since
     * "completed" next to seven failed files would be a lie.
     */
    suspend fun finishRun(
        outcome: RunOutcome? = null,
        note: String? = null
    ) = withContext(Dispatchers.IO) {
        val runId = currentRunId ?: return@withContext
        currentRunId = null
        val p = _progress.value
        val resolved = outcome ?: when {
            p.failedFiles > 0 && p.doneFiles > p.failedFiles -> RunOutcome.PARTIAL
            p.failedFiles > 0 && p.doneFiles == p.failedFiles -> RunOutcome.FAILED
            else -> RunOutcome.COMPLETED
        }
        runCatching {
            runDao.setUploadCounts(
                id = runId,
                uploaded = (p.doneFiles - p.failedFiles).coerceAtLeast(0),
                failed = p.failedFiles,
                bytes = p.bytesUploaded
            )
            runDao.finish(runId, resolved, System.currentTimeMillis(), note)
            runDao.trimTo(KEEP_RUNS)
        }
        // Once per run is often enough to keep the history table tidy, and here — after the run row
        // is closed — a failure cannot affect the outcome that was just written.
        runCatching { versionDao.deleteOrphans() }
        Log.i(tag, "run $runId finished as $resolved${note?.let { " ($it)" } ?: ""}")
    }

    /** Repairs rows left RUNNING by a killed process; called once when the app starts. */
    suspend fun closeStaleRuns() = withContext(Dispatchers.IO) {
        runCatching { runDao.closeStaleRuns(System.currentTimeMillis(), currentRunId ?: -1L) }
            .getOrDefault(0)
    }

    fun recentRunsFlow(limit: Int = 100) = runDao.recentRunsFlow(limit)

    fun runFlow(runId: Long) = runDao.byIdFlow(runId)

    fun runCountFlow() = runDao.runCountFlow()

    /** The files a single run touched, for the "tap a backup → see what changed" detail screen. */
    fun filesForRunFlow(runId: Long, limit: Int = 500) = dao.filesForRunFlow(runId, limit)

    fun runBreakdownFlow(runId: Long) = dao.runBreakdownFlow(runId)

    suspend fun lastFinishedRun(): BackupRun? = withContext(Dispatchers.IO) { runDao.lastFinishedRun() }

    suspend fun countForRun(runId: Long): Int = withContext(Dispatchers.IO) { dao.countForRun(runId) }

    /**
     * Uploads every PENDING file one at a time (deliberately not parallel, to stay well under
     * Telegram's per-account rate limits) and only counts a file as done once Telegram has
     * confirmed it. Returns when the queue is drained or the coroutine is cancelled — a
     * cancelled file goes back to PENDING rather than being lost.
     */
    suspend fun runBackupQueue(
        categoryFilter: BackupCategory? = null,
        onEachDone: suspend (FileRecord, Boolean) -> Unit = { _, _ -> }
    ) {
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
                    // Closed here rather than left to the caller: a run that never had a
                    // destination did not fail, it never started, and the timeline should say so.
                    finishRun(RunOutcome.BLOCKED, "No backup destination configured")
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
                // categoryFilter == null means "the normal full run", which still has to respect
                // which categories the user has unchecked on the dashboard — otherwise a category
                // toggled off only ever affected future scans, not files already queued from
                // before the toggle was flipped.
                val enabledCategories = if (categoryFilter == null) settings.enabledCategories.first() else null
                val total = if (categoryFilter == null) dao.pendingCount() else dao.pendingCountForCategory(categoryFilter)
                val totalBytes = if (categoryFilter == null) dao.pendingBytes() else dao.pendingBytesForCategory(categoryFilter)

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

                    val batch = nextBatch(order, categoryFilter)
                        .filter { attempted.add(it.id) }
                        .filter { enabledCategories == null || it.category in enabledCategories }
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
                        // One small UPDATE per file, so a run killed mid-way still shows an honest
                        // count on the timeline instead of zero.
                        currentRunId?.let { runId ->
                            runCatching { runDao.setUploadCounts(runId, done - failed, failed, bytesDone) }
                        }
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

    /** Drains whichever end of the queue the user asked for, optionally restricted to one category. */
    private suspend fun nextBatch(order: UploadOrder, categoryFilter: BackupCategory?): List<FileRecord> =
        if (categoryFilter == null) {
            when (order) {
                UploadOrder.OLDEST_FIRST -> dao.nextPendingBatch(BATCH_SIZE)
                UploadOrder.NEWEST_FIRST -> dao.nextPendingNewest(BATCH_SIZE)
                UploadOrder.SMALLEST_FIRST -> dao.nextPendingSmallest(BATCH_SIZE)
            }
        } else {
            when (order) {
                UploadOrder.OLDEST_FIRST -> dao.nextPendingBatchForCategory(categoryFilter, BATCH_SIZE)
                UploadOrder.NEWEST_FIRST -> dao.nextPendingNewestForCategory(categoryFilter, BATCH_SIZE)
                UploadOrder.SMALLEST_FIRST -> dao.nextPendingSmallestForCategory(categoryFilter, BATCH_SIZE)
            }
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
            val uploadedAt = System.currentTimeMillis()
            dao.markUploaded(record.id, messageId, chatId, uploadedAt, currentRunId)
            // History is written from the pre-upload snapshot, which is the whole point: these are
            // the bytes that were just sent, and the row above has already moved on.
            recordVersion(record, messageId, chatId, uploadedAt)
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
    suspend fun restoreFile(record: FileRecord): File = restoreInto(record, restoreDir())

    /**
     * The one download path, shared by the single-file restore and by the bulk migration. [dir] is
     * where the bytes land: Downloads/AirDrive for a one-off, a per-category subfolder for a
     * migration, so a new phone does not receive ten thousand files in one flat folder.
     */
    private suspend fun restoreInto(record: FileRecord, dir: File): File = withContext(Dispatchers.IO) {
        val messageId = record.telegramMessageId
            ?: throw IllegalStateException("No Telegram message was recorded for this file")
        val chatId = record.destinationChannelId.takeIf { it != 0L }
            ?: throw IllegalStateException("No Telegram chat was recorded for this file")

        _restoreState.value = RestoreState(record.displayName, totalBytes = record.sizeBytes)
        try {
            if (!tdClient.awaitReady(30_000)) throw IllegalStateException("Not signed in to Telegram")
            val fetched = tdClient.downloadMessageFile(chatId, messageId)
            val target = uniqueFile(dir, record.displayName.ifBlank { fetched.fileName })
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

    // --------------------------------------------------- deleted file protection (wishlist item 4)

    /** Files that vanished from the phone but whose Telegram copy is still there. */
    fun cloudOnlyFlow(query: String = "", limit: Int = 300) = dao.cloudOnlyFlow(query, limit)

    /** Badge count for the dashboard: how many files are cloud-only right now. */
    fun missingCountFlow() = dao.missingCountFlow()

    /** How much data exists only in Telegram — the "protected" figure on the deleted files screen. */
    fun cloudOnlyBytesFlow() = dao.cloudOnlyBytesFlow()

    /** "Keep forever" pins one file against the optional auto-delete sweep. */
    suspend fun setKeepForever(id: Long, keep: Boolean) = withContext(Dispatchers.IO) {
        dao.setKeepForever(id, keep)
    }

    /**
     * Puts a restored file back into the "present locally" state. Called after a successful
     * restore so a file the user pulled back does not keep sitting in the deleted list.
     */
    suspend fun markRestoredLocally(record: FileRecord) = withContext(Dispatchers.IO) {
        runCatching {
            dao.markRestored(record.id, System.currentTimeMillis())
            // The restore lands in Downloads/AirDrive, not at the original path, so the row is
            // deliberately not marked PRESENT — the next scan will find the new copy on its own
            // and the user still wants the Telegram copy either way. FREED/MISSING both become
            // UNKNOWN, which reads as "a copy is on this phone somewhere".
            dao.setLocalState(record.id, LocalState.UNKNOWN, System.currentTimeMillis())
        }
    }

    /**
     * Deletes the Telegram copy of a file the phone no longer has, then drops the row. This is
     * the one genuinely destructive operation in the app: after it, the file exists nowhere.
     * The UI gates it behind an explicit confirmation, and the record is only removed once
     * Telegram has confirmed the delete — a failed delete leaves everything as it was.
     */
    suspend fun purgeRemoteCopy(record: FileRecord): Result<Unit> = withContext(Dispatchers.IO) {
        val messageId = record.telegramMessageId
        val chatId = record.destinationChannelId.takeIf { it != 0L }
        if (messageId == null || chatId == null) {
            // Nothing to delete in Telegram; the row is stale bookkeeping, so removing it is safe.
            runCatching { dao.deleteById(record.id) }
            return@withContext Result.success(Unit)
        }
        runCatching {
            if (!tdClient.awaitReady(30_000)) throw IllegalStateException("Not signed in to Telegram")
            tdClient.deleteMessages(chatId, longArrayOf(messageId))
            dao.deleteById(record.id)
            Log.i(tag, "purged Telegram copy of ${record.displayName}")
            // Log.i hands back an Int, which would make this Result<Int>. The caller only cares
            // whether the delete threw, so the block is closed out at Unit deliberately.
            Unit
        }
    }

    /**
     * The optional "auto-delete after X days" sweep, run at the end of a backup. Off unless the
     * user turned it on; skips anything pinned with "keep forever" and anything the cleanup
     * assistant freed. Returns how many copies were removed.
     *
     * Each candidate is confirmed against Telegram first: if the probe cannot reach Telegram the
     * file is left alone, because "I could not check" must never be treated as permission to
     * delete. A probe that comes back Missing means the copy is already gone, so the row is
     * dropped without a delete call.
     */
    suspend fun runAutoPurgeSweep(): Int = withContext(Dispatchers.IO) {
        if (!settings.autoDeleteMissingEnabled.first()) return@withContext 0
        val days = settings.autoDeleteMissingDays.first()
        val cutoff = System.currentTimeMillis() - days * DAY_MILLIS
        val candidates = runCatching { dao.autoPurgeCandidates(cutoff, AUTO_PURGE_LIMIT) }
            .getOrDefault(emptyList())
        if (candidates.isEmpty()) return@withContext 0
        if (!tdClient.awaitReady(30_000)) {
            Log.w(tag, "auto-purge skipped: Telegram not ready")
            return@withContext 0
        }

        var removed = 0
        for (record in candidates) {
            if (!currentCoroutineContext().isActive) break
            val messageId = record.telegramMessageId ?: continue
            val chatId = record.destinationChannelId.takeIf { it != 0L } ?: continue
            when (tdClient.probeMessage(chatId, messageId)) {
                is RemoteFile.Unknown -> {
                    Log.w(tag, "auto-purge stopped: cannot verify ${record.displayName}")
                    break
                }
                is RemoteFile.Missing -> {
                    runCatching { dao.deleteById(record.id) }
                    removed++
                }
                is RemoteFile.Present -> {
                    val ok = runCatching {
                        tdClient.deleteMessages(chatId, longArrayOf(messageId))
                        dao.deleteById(record.id)
                    }.isSuccess
                    if (ok) removed++
                }
            }
        }
        if (removed > 0) Log.i(tag, "auto-purge removed $removed cloud-only file(s) after $days days")
        removed
    }

    // ------------------------------------------- device-to-device migration (wishlist item 8)

    /**
     * Per-category "what can still be pulled down onto this phone", which is what the migration
     * picker lists. Counts only files this phone has not restored yet, so the numbers shrink as a
     * migration runs and reach zero when it is done.
     */
    fun restorableTotalsFlow() = dao.restorableTotalsFlow()

    /** How many files have been pulled back so far, across every category. */
    fun restoredCountFlow() = dao.restoredCountFlow()

    /** The file count a migration over [categories] would attempt, using the same skip rule. */
    suspend fun migrationQueueSize(categories: Set<BackupCategory>, skipRestored: Boolean): Int =
        withContext(Dispatchers.IO) {
            categories.sumOf {
                runCatching { dao.restoreQueueCount(it.name, skipRestored) }.getOrDefault(0)
            }
        }

    /**
     * Called the moment the button is tapped, before WorkManager has started anything, so the screen
     * has something to show and cannot be tapped twice. [MigrationWorker] takes it from here.
     */
    fun markMigrationQueued() {
        _migration.value = MigrationState(running = true, queued = true)
    }

    /**
     * Records a cancellation the worker cannot report itself: work cancelled while still enqueued
     * never runs, so nothing else would ever clear [MigrationState.running].
     */
    fun markMigrationCancelled() {
        val current = _migration.value
        if (!current.running) return
        _migration.value = current.copy(
            running = false,
            queued = false,
            cancelled = true,
            currentFile = null
        )
    }

    /** Clears the result banner so the screen goes back to the picker. */
    fun clearMigrationState() {
        if (!_migration.value.running) _migration.value = MigrationState()
    }

    /**
     * "Start over": forgets which files were restored so they can all be pulled again. Pass null
     * for every category. Nothing is deleted — this only clears AirDrive's own bookkeeping.
     */
    suspend fun clearRestoreMarks(category: BackupCategory?): Int = withContext(Dispatchers.IO) {
        runCatching { dao.clearRestoreMarks(category?.name ?: "") }.getOrDefault(0)
    }

    /**
     * The migration itself, driven by [MigrationWorker] so it survives the screen, the app being
     * swiped away, and the process being killed — a 40 GB restore gets none of those courtesies
     * from a coroutine owned by a composable. Progress goes out through [migration], and because
     * the repository is a process singleton the screen sees it whether or not it started the run.
     *
     * One category at a time, in enum order so photos arrive before the long tail of "other files",
     * and in pages rather than one giant query — a queue of 20,000 rows read in one go is a needless
     * spike on a phone that is also writing gigabytes to disk.
     *
     * The paging cursor is the restore marker itself: each file that lands stops being returned by
     * the queue query, so the next page is genuinely the next hundred files. [seen] covers the one
     * case that marker cannot, a file that failed and therefore still looks unrestored — without a
     * memo of what this run already tried, the same page would come back forever.
     */
    suspend fun migrateNow(categories: Set<BackupCategory>, skipRestored: Boolean) {
        val ordered = BackupCategory.values().filter { it in categories }
        if (ordered.isEmpty()) {
            _migration.value = MigrationState(finished = true)
            return
        }
        _migration.value = MigrationState(running = true)
        try {
            if (!tdClient.awaitReady(60_000)) throw IllegalStateException("Not signed in to Telegram")
            // "Pull everything down again" is implemented by forgetting what was restored and then
            // running the ordinary resumable loop, so there is only ever one loop to reason about.
            if (!skipRestored) {
                ordered.forEach { runCatching { dao.clearRestoreMarks(it.name) } }
            }
            val total = ordered.sumOf {
                runCatching { dao.restoreQueueCount(it.name, true) }.getOrDefault(0)
            }
            _migration.value = _migration.value.copy(filesTotal = total)
            if (total == 0) {
                _migration.value = _migration.value.copy(running = false, finished = true)
                return
            }

            for (category in ordered) {
                if (!currentCoroutineContext().isActive) break
                val dir = File(restoreDir(), categoryFolder(category)).apply { mkdirs() }
                val seen = HashSet<Long>()
                while (currentCoroutineContext().isActive) {
                    val page = dao.restoreQueue(category.name, true, MIGRATION_PAGE)
                    val fresh = page.filterNot { it.id in seen }
                    if (fresh.isEmpty()) break
                    fresh.forEach { seen.add(it.id) }
                    for (record in fresh) {
                        if (!currentCoroutineContext().isActive) break
                        restoreOneForMigration(record, category, dir)
                    }
                }
            }

            val done = _migration.value
            _migration.value = done.copy(running = false, finished = true, currentFile = null)
            Log.i(tag, "migration finished: ${done.filesDone} restored, ${done.filesFailed} failed")
        } catch (e: CancellationException) {
            _migration.value = _migration.value.copy(
                running = false,
                cancelled = true,
                currentFile = null
            )
            throw e
        } catch (e: Exception) {
            Log.w(tag, "migration stopped: ${e.message}")
            _migration.value = _migration.value.copy(
                running = false,
                currentFile = null,
                error = e.message?.take(300) ?: e.javaClass.simpleName
            )
        } finally {
            // Otherwise the Restore screen is left showing the last file this migration touched.
            clearRestoreState()
        }
    }

    /**
     * One file inside a migration. A single failure must not end the run — a message deleted from
     * the channel by hand, or one file the download stalls on, should cost that file and nothing
     * more — so everything except cancellation is counted and stepped over.
     */
    private suspend fun restoreOneForMigration(
        record: FileRecord,
        category: BackupCategory,
        dir: File
    ) {
        _migration.value = _migration.value.copy(
            currentFile = record.displayName,
            currentCategory = category
        )
        val restored = try {
            restoreInto(record, dir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "migration could not restore ${record.displayName}: ${e.message}")
            null
        }
        if (restored == null) {
            _migration.value = _migration.value.copy(filesFailed = _migration.value.filesFailed + 1)
            return
        }
        // Marking each file as it lands is what makes a migration resumable: close the app halfway
        // through and the next run picks up from the first file that never arrived.
        markRestoredLocally(record)
        _migration.value = _migration.value.copy(
            filesDone = _migration.value.filesDone + 1,
            bytesDone = _migration.value.bytesDone + restored.length()
        )
    }

    /**
     * Subfolder name for a category. Duplicates the labels the UI uses rather than importing them,
     * because the data layer has no business reaching up into ui.screens — and these names are
     * additionally constrained: they end up on the filesystem, so they must stay path-safe.
     */
    private fun categoryFolder(category: BackupCategory): String = when (category) {
        BackupCategory.PHOTOS -> "Photos"
        BackupCategory.VIDEOS -> "Videos"
        BackupCategory.PDFS -> "PDFs"
        BackupCategory.WORD_EXCEL -> "Documents"
        BackupCategory.AUDIO -> "Audio"
        BackupCategory.CALL_RECORDINGS -> "Call recordings"
        BackupCategory.OTHER_FILES -> "Other files"
    }

    // ------------------------------------------- storage cleanup assistant (wishlist item 9)

    /**
     * "You can safely free" per category: uploaded, confirmed to have a Telegram message, still on
     * this phone, and reachable by real path. SAF-only documents are excluded by the query, because
     * AirDrive cannot reliably delete them and a cleanup list that lies about what it can free is
     * worse than a shorter one.
     */
    fun cleanupTotalsFlow() = dao.cleanupTotalsFlow()

    /** Running total of what past cleanups have already freed, for the header on that screen. */
    fun freedBytesFlow() = dao.freedBytesFlow()

    /** Largest first, since the point of the screen is space. [category] null means all of them. */
    fun cleanupCandidatesFlow(
        category: BackupCategory? = null,
        verifiedOnly: Boolean = false,
        limit: Int = CLEANUP_LIMIT
    ) = dao.cleanupCandidatesFlow(category?.name ?: "", verifiedOnly, limit)

    /**
     * Deletes local copies, and only after Telegram has confirmed, file by file, that it still holds
     * each one. This is the rule the whole feature rests on: the phone's copy is the one the user
     * can still see, so the burden of proof sits here and not on the user's optimism.
     *
     * Each file is probed immediately before its own delete. A probe that comes back [RemoteFile
     * .Unknown] stops the run — "I could not check" is never permission to delete — and one that
     * comes back Missing, or Present at a different size, deletes nothing and puts the file back in
     * the upload queue instead. A confirmed copy is also recorded as VERIFIED, since the probe is
     * exactly the check the verification sweep would have done.
     *
     * [onProgress] is called with (done, total) so the screen can show a bar; the caller decides
     * how often to look at it.
     */
    suspend fun freeLocalCopies(
        records: List<FileRecord>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): CleanupResult = withContext(Dispatchers.IO) {
        if (records.isEmpty()) return@withContext CleanupResult()
        if (!tdClient.awaitReady(30_000)) {
            return@withContext CleanupResult(
                stoppedReason = "Not signed in to Telegram, so nothing could be checked"
            )
        }

        var freed = 0
        var freedBytes = 0L
        var repaired = 0
        var failed = 0
        var stopped: String? = null

        records.forEachIndexed { index, record ->
            if (stopped != null) return@forEachIndexed
            if (!currentCoroutineContext().isActive) {
                stopped = "Cleanup was cancelled"
                return@forEachIndexed
            }
            onProgress(index, records.size)

            val messageId = record.telegramMessageId
            val chatId = record.destinationChannelId.takeIf { it != 0L }
            if (messageId == null || chatId == null) {
                // No Telegram copy recorded at all: nothing here is safe to delete.
                repaired += requeueOne(record.id)
                return@forEachIndexed
            }

            when (val remote = tdClient.probeMessage(chatId, messageId)) {
                is RemoteFile.Unknown -> {
                    stopped = "AirDrive could not reach Telegram to check the rest, so it stopped"
                    Log.w(tag, "cleanup stopped at ${record.displayName}: ${remote.reason}")
                }
                is RemoteFile.Missing -> {
                    Log.w(tag, "cleanup: Telegram copy of ${record.displayName} is gone (${remote.reason})")
                    runCatching {
                        dao.setVerifyState(record.id, VerifyState.MISSING_REMOTE, System.currentTimeMillis())
                    }
                    repaired += requeueOne(record.id)
                }
                is RemoteFile.Present -> {
                    val mismatch = remote.sizeBytes > 0 && record.sizeBytes > 0 &&
                        remote.sizeBytes != record.sizeBytes
                    if (mismatch) {
                        Log.w(
                            tag,
                            "cleanup: ${record.displayName} is ${record.sizeBytes} here but " +
                                "${remote.sizeBytes} in Telegram; re-uploading instead of deleting"
                        )
                        runCatching {
                            dao.setVerifyState(record.id, VerifyState.SIZE_MISMATCH, System.currentTimeMillis())
                        }
                        repaired += requeueOne(record.id)
                    } else {
                        runCatching {
                            dao.setVerifyState(record.id, VerifyState.VERIFIED, System.currentTimeMillis())
                        }
                        val bytes = deleteLocalCopy(record)
                        if (bytes == null) {
                            failed++
                        } else {
                            freed++
                            freedBytes += bytes
                        }
                    }
                }
            }
        }

        onProgress(records.size, records.size)
        Log.i(tag, "cleanup freed $freed file(s), ${formatSize(freedBytes)}; $repaired requeued, $failed failed")
        CleanupResult(freed, freedBytes, repaired, failed, stopped)
    }

    /** Puts one file back in the upload queue; returns 1 if the row was actually requeued. */
    private suspend fun requeueOne(id: Long): Int =
        runCatching { dao.requeueForRepair(id) }.getOrDefault(0).coerceIn(0, 1)

    /**
     * Removes the phone's copy and records it as FREED. Returns the bytes reclaimed, or null if the
     * delete failed — a file that will not delete must be reported, not quietly counted as space
     * saved. A file that has already gone is treated as freed: the space is genuinely back.
     *
     * Suspending because the FREED row is written here: the file and the database must agree, and
     * doing it in the caller's loop instead would leave a window where the copy is gone but the
     * index still says PRESENT, which is exactly the state that makes the next pass delete twice.
     */
    private suspend fun deleteLocalCopy(record: FileRecord): Long? {
        val path = runCatching { Uri.parse(record.uri).path }.getOrNull()
        if (path.isNullOrBlank()) return null
        val file = File(path)
        val size = if (file.isFile) file.length() else 0L
        val gone = !file.exists() || runCatching { file.delete() }.getOrDefault(false)
        if (!gone) {
            Log.w(tag, "cleanup could not delete $path")
            return null
        }
        runCatching { dao.markFreed(record.id, System.currentTimeMillis()) }
        // Without this the gallery keeps showing thumbnails for files that are no longer there,
        // which looks exactly like the cleanup having silently failed.
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        }
        return if (size > 0) size else record.sizeBytes
    }

    // ------------------------------------------- backup verification (wishlist item 15)

    /** How many uploaded files sit in each verify state, for the summary on the verify screen. */
    fun verifyBreakdownFlow() = dao.verifyBreakdownFlow()

    /** The files a sweep took issue with, most recent finding first. */
    fun verifyProblemsFlow(limit: Int = VERIFY_PROBLEM_LIMIT) = dao.verifyProblemsFlow(limit)

    /** Badge count for the dashboard, in the same spirit as the deleted-files one. */
    fun verifyProblemCountFlow() = dao.verifyProblemCountFlow()

    /**
     * Asks Telegram, file by file, whether it still holds what the index claims. An upload that
     * "succeeded" months ago is only a database row; this is the pass that turns it into a fact.
     *
     * Never-checked files go first, then the ones checked longest ago, so repeated small sweeps
     * rotate through the whole library instead of re-checking the same head of the list. A file that
     * turns out to be missing, or to be a different size than the one recorded, is put back in the
     * upload queue when the phone still has a copy — that is the only real repair available.
     *
     * A probe that cannot reach Telegram deliberately leaves the row exactly as it was, rather than
     * recording UNCHECKABLE: being offline says nothing about the file, and writing a state would
     * also stamp verifiedAtMillis and push the file to the back of the rotation, so the next sweep
     * would skip the very file it failed to check. Enough of those in a row and the sweep stops,
     * because past that point it is measuring the network, not the backup.
     */
    suspend fun verifyNow(
        onlyUnchecked: Boolean = false,
        budget: Int = VERIFY_BUDGET,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): VerifyResult = withContext(Dispatchers.IO) {
        if (!tdClient.awaitReady(30_000)) {
            return@withContext VerifyResult(
                stoppedReason = "Not signed in to Telegram, so nothing could be checked"
            )
        }
        val queue = runCatching { dao.verifyQueue(onlyUnchecked, budget) }.getOrDefault(emptyList())
        if (queue.isEmpty()) return@withContext VerifyResult()

        var checked = 0
        var confirmed = 0
        var problems = 0
        var requeued = 0
        var unreachable = 0
        var consecutiveUnreachable = 0
        var stopped: String? = null

        queue.forEachIndexed { index, record ->
            if (stopped != null) return@forEachIndexed
            if (!currentCoroutineContext().isActive) {
                stopped = "The check was cancelled"
                return@forEachIndexed
            }
            onProgress(index, queue.size)

            val messageId = record.telegramMessageId
            val chatId = record.destinationChannelId.takeIf { it != 0L }
            if (messageId == null || chatId == null) {
                // The row says uploaded but names no message: nothing to check, so treat it as a
                // finding and let the file go back through the queue if it is still here.
                problems++
                checked++
                runCatching {
                    dao.setVerifyState(record.id, VerifyState.MISSING_REMOTE, System.currentTimeMillis())
                }
                requeued += requeueOne(record.id)
                return@forEachIndexed
            }

            when (val remote = tdClient.probeMessage(chatId, messageId)) {
                is RemoteFile.Unknown -> {
                    unreachable++
                    consecutiveUnreachable++
                    if (consecutiveUnreachable >= VERIFY_UNREACHABLE_RUN) {
                        stopped = "Telegram stopped answering, so the rest was left unchecked"
                        Log.w(tag, "verify stopped after $consecutiveUnreachable unreachable probes")
                    }
                }
                is RemoteFile.Missing -> {
                    consecutiveUnreachable = 0
                    checked++
                    problems++
                    Log.w(tag, "verify: ${record.displayName} is not in Telegram (${remote.reason})")
                    runCatching {
                        dao.setVerifyState(record.id, VerifyState.MISSING_REMOTE, System.currentTimeMillis())
                    }
                    requeued += requeueOne(record.id)
                }
                is RemoteFile.Present -> {
                    consecutiveUnreachable = 0
                    checked++
                    val mismatch = remote.sizeBytes > 0 && record.sizeBytes > 0 &&
                        remote.sizeBytes != record.sizeBytes
                    if (mismatch) {
                        problems++
                        Log.w(
                            tag,
                            "verify: ${record.displayName} is ${record.sizeBytes} here but " +
                                "${remote.sizeBytes} in Telegram"
                        )
                        runCatching {
                            dao.setVerifyState(record.id, VerifyState.SIZE_MISMATCH, System.currentTimeMillis())
                        }
                        requeued += requeueOne(record.id)
                    } else {
                        confirmed++
                        runCatching {
                            dao.setVerifyState(record.id, VerifyState.VERIFIED, System.currentTimeMillis())
                        }
                    }
                }
            }
        }

        onProgress(queue.size, queue.size)
        Log.i(
            tag,
            "verify checked $checked of ${queue.size}: $confirmed ok, $problems problem(s), " +
                "$requeued requeued, $unreachable unreachable"
        )
        VerifyResult(checked, confirmed, problems, requeued, unreachable, stopped)
    }

    /**
     * The one-file version of the repair a sweep does automatically, for the "Upload again" button
     * on the verification screen. False means the phone no longer has a copy to upload — the file
     * exists nowhere AirDrive can reach, and no button can fix that.
     */
    suspend fun repairFile(record: FileRecord): Boolean = withContext(Dispatchers.IO) {
        requeueOne(record.id) == 1
    }

    /**
     * Drops a row whose file is gone from both sides. Nothing is deleted anywhere by this — the row
     * is bookkeeping about a file that no longer exists, and keeping it only means the verification
     * screen nags about a problem that cannot be solved.
     */
    suspend fun forgetRecord(record: FileRecord) = withContext(Dispatchers.IO) {
        runCatching { dao.deleteById(record.id) }
        // The history rows would otherwise be swept up later as orphans anyway; doing it here means
        // the version count on the dashboard drops the moment the file is forgotten.
        runCatching { versionDao.deleteForRecord(record.id) }
        Unit
    }

    // --------------------------------------------------- file version history (wishlist item 3)

    /**
     * Writes down the copy that was just uploaded. Called from [uploadOne] with the *pre-upload*
     * snapshot of the record, because that is what these bytes were: the row itself has already
     * been moved on to the new message id by the time this runs.
     *
     * Wrapped in runCatching on purpose. The file is in Telegram either way; a failed history write
     * must never turn a successful upload into a failed one, and the worst case is one missing
     * entry in a list of old copies.
     */
    private suspend fun recordVersion(
        record: FileRecord,
        messageId: Long,
        chatId: Long,
        uploadedAt: Long
    ) {
        runCatching {
            versionDao.insert(
                FileVersion(
                    recordId = record.id,
                    revision = record.revision,
                    displayName = record.displayName,
                    sizeBytes = record.sizeBytes,
                    modifiedAtMillis = record.modifiedAtMillis,
                    fingerprint = record.fingerprint,
                    chatId = chatId,
                    telegramMessageId = messageId,
                    uploadedAtMillis = uploadedAt,
                    runId = currentRunId
                )
            )
        }.onFailure { Log.w(tag, "could not record version of ${record.displayName}: ${it.message}") }
    }

    /** Every recorded copy of one file, newest first. Drives the history screen. */
    fun versionsFlow(recordId: Long) = versionDao.versionsFlow(recordId)

    /** Files that have more than one recorded copy — i.e. the ones with a history worth showing. */
    fun versionedFilesFlow(limit: Int = HISTORY_LIMIT) = versionDao.versionedFilesFlow(limit)

    /** Badge count for the dashboard entry. */
    fun versionedFileCountFlow() = versionDao.versionedFileCountFlow()

    fun versionCountFlow(recordId: Long) = versionDao.versionCountFlow(recordId)

    /** One query behind the whole history list's "3 versions kept" lines. */
    fun versionCountsFlow() = versionDao.versionCountsFlow()

    /**
     * Drops history rows whose file record has gone. Cheaper and far safer than a foreign key here
     * (see [FileVersion]): a hand-written migration whose constraints do not match what Room expects
     * is a crash on launch, while an orphaned row is invisible until this sweep collects it.
     */
    suspend fun pruneOrphanVersions(): Int = withContext(Dispatchers.IO) {
        runCatching { versionDao.deleteOrphans() }.getOrDefault(0)
    }

    /**
     * Pulls one *older* copy back out of Telegram. Everything about this is deliberately
     * non-destructive: it reuses [restoreInto], which lands bytes in Downloads/AirDrive under a
     * de-duplicated name, so the file currently on the phone is never touched and the two can be
     * compared before anything is thrown away.
     *
     * The name carries the revision — "report (v2).docx" — because the point of the feature is that
     * the user has two copies of the same file and needs to tell them apart. And unlike the ordinary
     * restore this does not call markRestoredLocally: fetching v2 of a v5 file says nothing about
     * whether the current version is back on the phone.
     */
    suspend fun restoreVersion(record: FileRecord, version: FileVersion): File {
        if (version.telegramMessageId == null) {
            throw IllegalStateException("No Telegram message was recorded for this version")
        }
        if (version.chatId == 0L) {
            throw IllegalStateException("No Telegram chat was recorded for this version")
        }
        return restoreInto(
            record.copy(
                displayName = versionedFileName(version.displayName, version.revision),
                sizeBytes = version.sizeBytes,
                destinationChannelId = version.chatId,
                telegramMessageId = version.telegramMessageId
            ),
            restoreDir()
        )
    }

    /** "report.docx" + 2 → "report (v2).docx". Extension-less names just get the suffix appended. */
    private fun versionedFileName(name: String, revision: Int): String {
        val safe = name.ifBlank { "file" }
        val dot = safe.lastIndexOf('.')
        // A leading dot is the whole name of a hidden file, not an extension, so it is left alone.
        return if (dot > 0) {
            "${safe.substring(0, dot)} (v$revision)${safe.substring(dot)}"
        } else {
            "$safe (v$revision)"
        }
    }

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

        /** How many run rows the timeline keeps. Roughly a year of twice-daily backups. */
        private const val KEEP_RUNS = 500

        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        /**
         * Cap on one auto-purge sweep. Deleting is irreversible, so a bounded batch per run is
         * preferable to churning through thousands of files in one go; the next run continues.
         */
        private const val AUTO_PURGE_LIMIT = 200

        /**
         * How many queued files one migration page pulls from the database. Small enough that the
         * first file starts downloading immediately, large enough that a 20,000-file restore does
         * not spend its life running queries.
         */
        private const val MIGRATION_PAGE = 100

        /**
         * How many cleanup candidates the picker lists. The screen is a hand-picking exercise, not a
         * file manager: 300 of the largest files is already far more than anyone scrolls, and the
         * per-category totals above the list carry the real "you could free 28.7 GB" figure.
         */
        private const val CLEANUP_LIMIT = 300

        /**
         * How many files one verification sweep asks Telegram about. Each probe is a round trip, so
         * this is a few minutes of work, not seconds; the queue is ordered oldest-check-first so
         * running it again picks up where the last one stopped rather than repeating itself.
         */
        private const val VERIFY_BUDGET = 200

        /** How many problem files the verification screen lists. */
        private const val VERIFY_PROBLEM_LIMIT = 200

        /**
         * Unreachable probes in a row before a sweep gives up. One or two are a blip worth riding
         * out; five in a row means the connection is gone and every further probe is wasted.
         */
        private const val VERIFY_UNREACHABLE_RUN = 5

        /**
         * How many files with a history the list shows. The screen exists to answer "I need the
         * version from before I broke it", which is always about a file the user has in mind and
         * changed recently, so the newest few hundred is the whole useful range.
         */
        private const val HISTORY_LIMIT = 300

        private const val PRIMARY_STORAGE = "/storage/emulated/0"

        @Volatile private var instance: BackupRepository? = null

        fun get(context: Context): BackupRepository =
            instance ?: synchronized(this) {
                instance ?: BackupRepository(context.applicationContext).also { instance = it }
            }
    }
}
