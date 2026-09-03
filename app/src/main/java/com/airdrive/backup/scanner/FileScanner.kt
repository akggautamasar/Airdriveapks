package com.airdrive.backup.scanner

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.KnownFile
import com.airdrive.backup.data.db.LocalState
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.util.Fingerprint
import com.airdrive.backup.util.StorageAccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import java.io.File

/**
 * Live progress and the final tally of one scan. The counters are what the incremental summary
 * is built from: "10 000 scanned → 9 850 already backed up → 150 new/changed → upload 150".
 */
data class ScanProgress(
    val filesScanned: Int,
    val filesQueued: Int,
    val currentDir: String,
    val wholeDevice: Boolean = false,
    /** Whole-device scanning is on but the storage permission is missing. */
    val accessBlocked: Boolean = false,
    /** Files the user's own rules kept out: excluded folders, or over the size cap. */
    val filesExcluded: Int = 0,
    /** Seen on disk and already backed up, byte for byte. Nothing to do for these. */
    val filesUnchanged: Int = 0,
    /** Newly discovered files, queued for upload. */
    val filesNew: Int = 0,
    /** Tracked files whose bytes changed; queued again as a new revision. */
    val filesModified: Int = 0,
    /** Tracked files found at a new path; repointed instead of re-uploaded. */
    val filesRenamed: Int = 0,
    /** Tracked files that have vanished from the phone. The Telegram copies are kept. */
    val filesMissing: Int = 0,
    /** Byte-identical second copies of files already in Telegram. */
    val filesDuplicate: Int = 0,
    /**
     * False when the walk was cut short — cancelled, or storage access missing. The deletion
     * sweep only runs on a complete walk, so a half-finished scan can never conclude that half
     * the phone was deleted.
     */
    val complete: Boolean = false
)

class FileScanner(private val context: Context) {

    private val tag = "AirDrive.Scanner"
    private val db = AppDatabase.get(context)
    private val settings = SettingsStore(context)

    /**
     * Discovers files and queues only the ones that actually need uploading.
     *
     * The expensive part of a repeat backup is not the upload, it is deciding what to upload. A
     * file already in the database is compared on size and last-modified time first, which is
     * free; only if that snapshot moved is the file re-read and re-fingerprinted. For the ~98% of
     * a library that did not change, the cost is one hash-map lookup.
     *
     * Four outcomes per file: unchanged (skip), modified (requeue as the next revision),
     * renamed/moved (repoint the existing row, no upload at all), new (insert). Files that
     * disappeared are swept afterwards — and only when the walk actually completed.
     */
    suspend fun scanAll(onProgress: (ScanProgress) -> Unit = {}): ScanProgress {
        val dao = db.fileRecordDao()
        val requestWholeDevice = settings.scanWholeDevice.first()
        val hasAccess = StorageAccess.hasFullAccess(context)
        val wholeDevice = requestWholeDevice && hasAccess

        if (wholeDevice && !settings.safQueuePurged.first()) {
            val dropped = dao.deleteUnsentSafRows()
            settings.setSafQueuePurged(true)
            if (dropped > 0) Log.i(tag, "dropped $dropped unsent SAF rows superseded by direct paths")
        }

        // One pass over the tracked rows, then everything else is in memory. The old scanner ran
        // two SELECTs per file, which is what made a full-device scan unusable.
        val known = HashMap<String, KnownFile>()
        val uploadedByPrint = HashMap<String, KnownFile>()
        for (row in dao.knownFiles()) {
            known[row.uri] = row
            if (row.status == UploadStatus.UPLOADED && !uploadedByPrint.containsKey(row.fingerprint)) {
                uploadedByPrint[row.fingerprint] = row
            }
        }

        val session = Session(
            includeSmall = settings.includeSmallFiles.first(),
            enabled = settings.enabledCategories.first(),
            channels = settings.allChannels.first().perCategory,
            known = known,
            uploadedByPrint = uploadedByPrint,
            wholeDevice = wholeDevice,
            excluded = settings.excludedPaths.first(),
            maxSizeBytes = settings.maxFileSizeMb.first().coerceAtLeast(0L) * 1024L * 1024L,
            onProgress = onProgress
        )

        if (wholeDevice) {
            val roots = StorageAccess.scanRoots(context, settings.includeSdCard.first())
            Log.i(tag, "scanning ${roots.size} root(s): ${roots.joinToString { it.absolutePath }}")
            for (root in roots) {
                session.addSweepRoot(root.absolutePath)
                walkFiles(root, session)
            }
        } else {
            for (treeUri in settings.authorizedTreeUris.first()) walkSafTree(treeUri, session)
        }

        session.flush()
        session.resolveRenames()
        session.applyReappeared()
        val missing = session.sweepMissing()

        val result = ScanProgress(
            filesScanned = session.scanned,
            filesQueued = session.queuedNew + session.requeued,
            currentDir = "",
            wholeDevice = wholeDevice,
            accessBlocked = requestWholeDevice && !hasAccess,
            filesExcluded = session.excludedCount,
            filesUnchanged = session.unchanged,
            filesNew = session.queuedNew,
            filesModified = session.requeued,
            filesRenamed = session.renamed,
            filesMissing = missing,
            filesDuplicate = session.duplicates,
            complete = !session.aborted
        )
        Log.i(
            tag,
            "scan done: ${result.filesScanned} scanned, ${result.filesUnchanged} unchanged, " +
                "${result.filesNew} new, ${result.filesModified} modified, " +
                "${result.filesRenamed} renamed, ${result.filesMissing} missing, " +
                "${result.filesDuplicate} duplicate, complete=${result.complete}"
        )
        return result
    }

    /** Iterative walk of a real directory tree — no recursion, and symlink loops are broken. */
    private suspend fun walkFiles(root: File, session: Session) {
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        val visited = HashSet<String>()

        while (stack.isNotEmpty()) {
            if (!currentCoroutineContext().isActive) {
                session.aborted = true
                return
            }
            val dir = stack.removeLast()
            val canonical = try {
                dir.canonicalPath
            } catch (e: Exception) {
                dir.absolutePath
            }
            if (!visited.add(canonical)) continue

            session.report(dir.absolutePath)
            val children = dir.listFiles()
            if (children == null) {
                // Unreadable folder. Its contents are unknown, so nothing filed under it may be
                // declared deleted later on.
                session.markBlind(dir.absolutePath)
                continue
            }

            for (child in children) {
                if (child.isDirectory) {
                    val childPath = child.absolutePath.lowercase()
                    if (Categorizer.isSkippedDir(child.name, childPath) ||
                        session.isExcluded(childPath)
                    ) {
                        session.markBlind(child.absolutePath)
                        continue
                    }
                    stack.addLast(child)
                    continue
                }
                if (!child.isFile) continue
                val size = child.length()
                val uriString = Uri.fromFile(child).toString()
                // Uninteresting, but it does exist — record that so the sweep leaves it alone.
                if (Categorizer.isSkippedFile(child.name, size)) {
                    session.noteOnDisk(uriString)
                    continue
                }

                session.consider(
                    uri = uriString,
                    name = child.name,
                    size = size,
                    modified = child.lastModified(),
                    pathLower = child.absolutePath.lowercase()
                ) { Fingerprint.compute(child, size) }
            }
        }
    }

    /** Kept for devices/setups where All files access is unavailable or declined. */
    private suspend fun walkSafTree(treeUriString: String, session: Session) {
        val treeUri = Uri.parse(treeUriString)
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return
        }

        val stack = ArrayDeque<Pair<Uri, String>>()
        stack.addLast(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId) to "")

        while (stack.isNotEmpty()) {
            if (!currentCoroutineContext().isActive) {
                session.aborted = true
                return
            }
            val (childrenUri, pathSoFar) = stack.removeLast()
            session.report(pathSoFar)

            val cursor: Cursor = try {
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    ),
                    null, null, null
                ) ?: continue
            } catch (e: Exception) {
                continue
            }

            cursor.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val size = c.getLong(3)
                    val modified = c.getLong(4)
                    val pathLower = "$pathSoFar/$name".lowercase()

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!Categorizer.isSkippedDir(name, pathLower) && !session.isExcluded(pathLower)) {
                            stack.addLast(
                                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId) to
                                    "$pathSoFar/$name"
                            )
                        }
                        continue
                    }

                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    if (Categorizer.isSkippedFile(name, size)) {
                        session.noteOnDisk(docUri.toString())
                        continue
                    }

                    session.consider(
                        uri = docUri.toString(),
                        name = name,
                        size = size,
                        modified = modified,
                        pathLower = pathLower
                    ) { Fingerprint.compute(context, docUri, size) }
                }
            }
        }
    }

    /**
     * A file found at a path nobody has seen before, whose bytes are already in Telegram. It is
     * either a rename of [twinId] or a second copy sitting alongside it, and which one cannot be
     * known until the walk is over and the twin's own path has had its chance to turn up.
     */
    private data class RenameCandidate(
        val twinId: Long,
        val uri: String,
        val name: String,
        val modified: Long
    )

    /**
     * Scan state plus the shared per-file decision logic. Everything the decision needs is loaded
     * once up front and kept in memory for the duration of the walk.
     */
    private inner class Session(
        val includeSmall: Boolean,
        val enabled: Set<BackupCategory>,
        val channels: Map<BackupCategory, Long>,
        /** uri → tracked row, for every file already in the database. */
        val known: MutableMap<String, KnownFile>,
        /** fingerprint → an uploaded row holding those bytes; the basis of rename detection. */
        val uploadedByPrint: MutableMap<String, KnownFile>,
        val wholeDevice: Boolean,
        /** Lower-case path fragments the user excluded; matched anywhere in the path. */
        val excluded: Set<String>,
        /** 0 = no cap. */
        val maxSizeBytes: Long,
        val onProgress: (ScanProgress) -> Unit
    ) {
        var scanned = 0
        var queuedNew = 0
        var requeued = 0
        var renamed = 0
        var unchanged = 0
        var duplicates = 0
        var excludedCount = 0

        /** Set when a walk returns early; disables the deletion sweep. */
        var aborted = false

        /** Row ids confirmed to exist on disk during this walk. */
        private val seenIds = HashSet<Long>()

        /** Rows found again after having been marked missing. */
        private val reappeared = ArrayList<Long>()

        /** Lower-case directory prefixes whose contents this scan could not see. */
        private val blindDirs = ArrayList<String>()

        /** Lower-case root prefixes this scan actually covered. */
        private val sweepRoots = ArrayList<String>()

        private val renameCandidates = ArrayList<RenameCandidate>()

        /** Fingerprints queued fresh in this scan, so two new copies count as one upload. */
        private val newPrints = HashSet<String>()

        private val batch = ArrayList<FileRecord>(INSERT_BATCH)
        private var lastReportAt = 0
        private var blindOverflow = false

        fun isExcluded(pathLower: String): Boolean =
            excluded.any { it.isNotEmpty() && pathLower.contains(it) }

        fun addSweepRoot(path: String) {
            sweepRoots.add(path.lowercase().trimEnd('/') + "/")
        }

        fun markBlind(path: String) {
            if (blindDirs.size >= MAX_BLIND_DIRS) {
                blindOverflow = true
                return
            }
            blindDirs.add(path.lowercase().trimEnd('/') + "/")
        }

        fun report(dir: String) {
            onProgress(
                ScanProgress(
                    filesScanned = scanned,
                    filesQueued = queuedNew + requeued,
                    currentDir = dir,
                    wholeDevice = wholeDevice,
                    filesExcluded = excludedCount,
                    filesUnchanged = unchanged,
                    filesNew = queuedNew,
                    filesModified = requeued,
                    filesRenamed = renamed
                )
            )
            lastReportAt = scanned
        }

        /** The file exists but is not worth inspecting; all this does is keep it off the sweep. */
        fun noteOnDisk(uri: String) {
            known[uri]?.let { seenIds.add(it.id) }
        }

        suspend fun consider(
            uri: String,
            name: String,
            size: Long,
            modified: Long,
            pathLower: String,
            fingerprintOf: () -> String
        ) {
            scanned++
            if (scanned - lastReportAt >= REPORT_EVERY) {
                report(pathLower.substringBeforeLast('/', pathLower))
            }

            val existing = known[uri]
            if (existing != null) {
                seenIds.add(existing.id)
                // The cheap check that makes a repeat backup fast: same size, same timestamp,
                // nothing to do. No read, no hash, no upload.
                if (existing.sizeBytes == size && existing.modifiedAtMillis == modified) {
                    unchanged++
                    if (existing.localState != LocalState.PRESENT) reappeared.add(existing.id)
                    return
                }
            }

            // The user's own rules apply to new and changed files alike; cheapest test first.
            if (!includeSmall && size < MIN_SIZE_BYTES) return
            if (isExcluded(pathLower)) {
                excludedCount++
                return
            }
            // Telegram itself refuses anything over 4GB, so queueing such a file only produces a
            // long upload that is guaranteed to fail.
            if (size > TELEGRAM_MAX_BYTES || (maxSizeBytes > 0L && size > maxSizeBytes)) {
                excludedCount++
                return
            }

            val category = Categorizer.categorize(name.lowercase(), pathLower)
            if (category !in enabled) return

            val fingerprint = try {
                fingerprintOf()
            } catch (e: Exception) {
                return
            }

            if (existing != null) {
                onChangedInPlace(existing, size, modified, fingerprint)
                return
            }

            // A path nobody has seen before. The bytes may still be ones we already hold, as
            // either a rename or a duplicate — deferred until the walk finishes.
            if (uploadedByPrint.containsKey(fingerprint)) {
                val twin = uploadedByPrint.getValue(fingerprint)
                if (renameCandidates.size < MAX_RENAME_CANDIDATES) {
                    renameCandidates.add(RenameCandidate(twin.id, uri, name, modified))
                } else {
                    duplicates++
                }
                return
            }

            // Two copies of the same brand-new file only need one upload.
            if (!newPrints.add(fingerprint)) {
                duplicates++
                return
            }

            known[uri] = KnownFile(
                id = PLACEHOLDER_ID,
                uri = uri,
                sizeBytes = size,
                modifiedAtMillis = modified,
                status = UploadStatus.PENDING,
                fingerprint = fingerprint,
                localState = LocalState.PRESENT
            )
            batch.add(
                FileRecord(
                    uri = uri,
                    displayName = name,
                    sizeBytes = size,
                    modifiedAtMillis = modified,
                    category = category,
                    fingerprint = fingerprint,
                    status = UploadStatus.PENDING,
                    destinationChannelId = channels[category] ?: 0L
                )
            )
            queuedNew++
            if (batch.size >= INSERT_BATCH) flush()
        }

        /**
         * Same path, different size or timestamp. If the bytes still hash the same then only the
         * timestamp moved — a copy, a touch, a media-scanner rewrite — and all that needs
         * updating is the snapshot. Otherwise the file was genuinely edited, and it goes back into
         * the queue as the next revision.
         */
        private suspend fun onChangedInPlace(
            existing: KnownFile,
            size: Long,
            modified: Long,
            fingerprint: String
        ) {
            val dao = db.fileRecordDao()
            if (fingerprint == existing.fingerprint) {
                unchanged++
                dao.touchSnapshot(existing.id, size, modified)
                known[existing.uri] = existing.copy(sizeBytes = size, modifiedAtMillis = modified)
                return
            }
            dao.requeueModified(existing.id, size, modified, fingerprint)
            requeued++
            known[existing.uri] = existing.copy(
                sizeBytes = size,
                modifiedAtMillis = modified,
                fingerprint = fingerprint,
                status = UploadStatus.PENDING,
                localState = LocalState.PRESENT
            )
        }

        suspend fun flush() {
            if (batch.isEmpty()) return
            db.fileRecordDao().insertAll(batch.toList())
            batch.clear()
        }

        /**
         * Decides what the deferred candidates really were. If the twin's own path also turned up
         * during the walk then both copies exist and the new one is a duplicate — the bytes are
         * already in Telegram, so there is nothing to upload. If the twin's path is gone, the file
         * was renamed or moved: the row is repointed at the new path and no upload happens at all.
         *
         * This is also what binds a row rebuilt from the Telegram manifest to the real file once
         * it is found again after a reinstall.
         */
        suspend fun resolveRenames() {
            if (renameCandidates.isEmpty()) return
            val dao = db.fileRecordDao()
            for (candidate in renameCandidates) {
                if (candidate.twinId in seenIds) {
                    duplicates++
                    continue
                }
                val moved = dao.repointToNewLocation(
                    id = candidate.twinId,
                    uri = candidate.uri,
                    name = candidate.name,
                    modified = candidate.modified
                ) > 0
                if (moved) {
                    renamed++
                    // The row now lives at the new path, so the sweep must not touch it.
                    seenIds.add(candidate.twinId)
                } else {
                    duplicates++
                }
            }
            renameCandidates.clear()
        }

        /** Files that had been marked missing and are back. */
        suspend fun applyReappeared() {
            if (reappeared.isEmpty()) return
            val dao = db.fileRecordDao()
            for (chunk in reappeared.chunked(CHUNK_IDS)) dao.markPresentBatch(chunk)
            Log.i(tag, "${reappeared.size} file(s) reappeared locally")
            reappeared.clear()
        }

        /**
         * Marks tracked files that no longer exist on the phone. This is the one part of a scan
         * that draws a destructive-sounding conclusion, so it is deliberately conservative and
         * declines to run at all rather than guess:
         *
         * - only after a complete whole-device walk (a SAF scan sees far too little of the phone);
         * - only rows whose uri is a real file path under a root that was actually walked;
         * - never rows under a folder the walk could not read, skipped, or the user excluded;
         * - never manifest rows (restored://), which have no local path to check to begin with;
         * - only rows that are currently PRESENT, so FREED and UNKNOWN are left alone.
         *
         * Without those guards, a phone with an unmounted SD card or a revoked permission would
         * quietly declare thousands of files deleted.
         */
        suspend fun sweepMissing(): Int {
            if (aborted || !wholeDevice || blindOverflow || sweepRoots.isEmpty()) {
                if (blindOverflow) Log.w(tag, "sweep skipped: too many unreadable folders")
                return 0
            }

            val gone = ArrayList<Long>()
            for (row in known.values) {
                if (row.id == PLACEHOLDER_ID) continue
                if (row.id in seenIds) continue
                if (row.localState != LocalState.PRESENT) continue
                if (!row.uri.startsWith(FILE_URI_PREFIX)) continue
                val pathLower = decodedPath(row.uri) ?: continue
                if (sweepRoots.none { pathLower.startsWith(it) }) continue
                if (blindDirs.any { pathLower.startsWith(it) }) continue
                if (isExcluded(pathLower)) continue
                gone.add(row.id)
            }
            if (gone.isEmpty()) return 0

            val now = System.currentTimeMillis()
            var marked = 0
            val dao = db.fileRecordDao()
            for (chunk in gone.chunked(CHUNK_IDS)) marked += dao.markMissingBatch(chunk, now)
            Log.i(tag, "sweep marked $marked file(s) as missing locally")
            return marked
        }

        /** file:// uris are percent-encoded; the sweep compares real paths. */
        private fun decodedPath(uri: String): String? =
            try {
                Uri.parse(uri).path?.lowercase()
            } catch (e: Exception) {
                null
            }
    }

    private companion object {
        const val INSERT_BATCH = 200
        const val MIN_SIZE_BYTES = 1024L
        const val REPORT_EVERY = 250

        /** SQLite's bound-variable ceiling is 999; 400 leaves room to spare. */
        const val CHUNK_IDS = 400

        /**
         * Renames are resolved after the walk, so candidates have to be buffered. The cap keeps a
         * pathological case (an entire library duplicated) from growing without bound; past it the
         * extra files are simply treated as duplicates, which is the safe answer — a duplicate
         * costs nothing, whereas a wrong rename would move a row off a file that still exists.
         */
        const val MAX_RENAME_CANDIDATES = 20_000

        /** Beyond this many unreadable folders the deletion sweep is not trustworthy at all. */
        const val MAX_BLIND_DIRS = 512

        const val FILE_URI_PREFIX = "file://"

        /** Rows queued during this scan that have no database id yet. */
        const val PLACEHOLDER_ID = 0L

        /** Telegram's own per-file ceiling (4GB with Premium, 2GB without). */
        const val TELEGRAM_MAX_BYTES = 4L * 1024L * 1024L * 1024L
    }
}
