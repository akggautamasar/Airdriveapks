package com.airdrive.backup.scanner

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.util.Fingerprint
import com.airdrive.backup.util.StorageAccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import java.io.File

data class ScanProgress(
    val filesScanned: Int,
    val filesQueued: Int,
    val currentDir: String,
    val wholeDevice: Boolean = false,
    /** Whole-device scanning is on but the storage permission is missing. */
    val accessBlocked: Boolean = false
)

class FileScanner(private val context: Context) {

    private val tag = "AirDrive.Scanner"
    private val db = AppDatabase.get(context)
    private val settings = SettingsStore(context)

    /**
     * Discovers files and inserts them as PENDING records.
     *
     * Default mode walks every folder under internal storage (plus removable cards) directly,
     * so the user never has to pick a folder. If All files access has not been granted the scan
     * falls back to whatever SAF trees were authorized, and reports accessBlocked so the UI can
     * ask for the permission.
     *
     * Already-backed-up files are recognized via [Fingerprint] even if renamed, so they are
     * never re-queued.
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

        val session = Session(
            includeSmall = settings.includeSmallFiles.first(),
            enabled = settings.enabledCategories.first(),
            channels = settings.allChannels.first().perCategory,
            knownUris = HashSet(dao.allUris()),
            uploadedPrints = HashSet(dao.uploadedFingerprints()),
            wholeDevice = wholeDevice,
            onProgress = onProgress
        )

        if (wholeDevice) {
            val roots = StorageAccess.scanRoots(context, settings.includeSdCard.first())
            Log.i(tag, "scanning ${roots.size} root(s): ${roots.joinToString { it.absolutePath }}")
            for (root in roots) walkFiles(root, session)
        } else {
            for (treeUri in settings.authorizedTreeUris.first()) walkSafTree(treeUri, session)
        }

        session.flush()
        return ScanProgress(
            filesScanned = session.scanned,
            filesQueued = session.queued,
            currentDir = "",
            wholeDevice = wholeDevice,
            accessBlocked = requestWholeDevice && !hasAccess
        )
    }

    /** Iterative walk of a real directory tree — no recursion, and symlink loops are broken. */
    private suspend fun walkFiles(root: File, session: Session) {
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        val visited = HashSet<String>()

        while (stack.isNotEmpty()) {
            if (!currentCoroutineContext().isActive) return
            val dir = stack.removeLast()
            val canonical = try {
                dir.canonicalPath
            } catch (e: Exception) {
                dir.absolutePath
            }
            if (!visited.add(canonical)) continue

            session.report(dir.absolutePath)
            val children = dir.listFiles() ?: continue

            for (child in children) {
                if (child.isDirectory) {
                    if (!Categorizer.isSkippedDir(child.name, child.absolutePath.lowercase())) {
                        stack.addLast(child)
                    }
                    continue
                }
                if (!child.isFile) continue
                val size = child.length()
                if (Categorizer.isSkippedFile(child.name, size)) continue

                session.consider(
                    uri = Uri.fromFile(child).toString(),
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
            if (!currentCoroutineContext().isActive) return
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
                        if (!Categorizer.isSkippedDir(name, pathLower)) {
                            stack.addLast(
                                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId) to
                                    "$pathSoFar/$name"
                            )
                        }
                        continue
                    }
                    if (Categorizer.isSkippedFile(name, size)) continue

                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
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
     * Scan state plus the shared per-file decision logic. Known URIs and uploaded fingerprints
     * are loaded once up front: the old scanner ran two SELECTs and a DataStore read for every
     * single file, which is what made a full-device scan unusable.
     */
    private inner class Session(
        val includeSmall: Boolean,
        val enabled: Set<BackupCategory>,
        val channels: Map<BackupCategory, Long>,
        val knownUris: MutableSet<String>,
        val uploadedPrints: Set<String>,
        val wholeDevice: Boolean,
        val onProgress: (ScanProgress) -> Unit
    ) {
        var scanned = 0
        var queued = 0
        private val batch = ArrayList<FileRecord>(INSERT_BATCH)
        private var lastReportAt = 0

        fun report(dir: String) {
            onProgress(ScanProgress(scanned, queued, dir, wholeDevice))
            lastReportAt = scanned
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
            if (scanned - lastReportAt >= 250) report(pathLower.substringBeforeLast('/', pathLower))
            if (uri in knownUris) return
            if (!includeSmall && size < MIN_SIZE_BYTES) return

            val category = Categorizer.categorize(name.lowercase(), pathLower)
            if (category !in enabled) return

            val fingerprint = try {
                fingerprintOf()
            } catch (e: Exception) {
                return
            }
            if (fingerprint in uploadedPrints) return

            knownUris.add(uri)
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
            queued++
            if (batch.size >= INSERT_BATCH) flush()
        }

        suspend fun flush() {
            if (batch.isEmpty()) return
            db.fileRecordDao().insertAll(batch.toList())
            batch.clear()
        }
    }

    private companion object {
        const val INSERT_BATCH = 200
        const val MIN_SIZE_BYTES = 1024L
    }
}
