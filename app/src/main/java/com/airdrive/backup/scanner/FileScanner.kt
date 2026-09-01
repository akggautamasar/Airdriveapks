package com.airdrive.backup.scanner

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.util.Fingerprint
import kotlinx.coroutines.flow.first

data class ScanProgress(val filesScanned: Int, val filesQueued: Int, val currentDir: String)

class FileScanner(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val settings = SettingsStore(context)

    /**
     * Walks every authorized SAF tree the user has granted, iteratively (no unbounded
     * recursion, no loading the whole tree into memory at once), and inserts newly
     * discovered files as PENDING records. Already-backed-up files are recognized via
     * [Fingerprint] even if renamed, so they are not re-queued.
     */
    suspend fun scanAll(onProgress: (ScanProgress) -> Unit = {}): ScanProgress {
        val includeSmall = settings.includeSmallFiles.first()
        val enabledCategories = settings.enabledCategories.first()
        val treeUris = settings.authorizedTreeUris.first()

        var scanned = 0
        var queued = 0

        for (treeUriString in treeUris) {
            val treeUri = Uri.parse(treeUriString)
            val rootDocId = try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (e: Exception) {
                continue
            }

            val stack = ArrayDeque<Pair<Uri, String>>()
            stack.addLast(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId) to "")

            while (stack.isNotEmpty()) {
                val (childrenUri, pathSoFar) = stack.removeLast()
                onProgress(ScanProgress(scanned, queued, pathSoFar))

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
                        val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                        val pathLower = "$pathSoFar/$name".lowercase()

                        if (isDir) {
                            if (Categorizer.isSkippedDir(name, pathLower)) continue
                            val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                            stack.addLast(childUri to "$pathSoFar/$name")
                            continue
                        }

                        scanned++
                        if (!includeSmall && size < 1024) continue

                        val category = Categorizer.categorize(name.lowercase(), pathLower)
                        if (category !in enabledCategories) continue

                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                        if (db.fileRecordDao().findByUri(docUri.toString()) != null) continue

                        val fingerprint = try {
                            Fingerprint.compute(context, docUri, size)
                        } catch (e: Exception) {
                            continue
                        }

                        val existing = db.fileRecordDao().findByFingerprint(fingerprint)
                        if (existing != null && existing.status == UploadStatus.UPLOADED) continue

                        val channelId = settings.channelFor(category).first()
                        db.fileRecordDao().insert(
                            FileRecord(
                                uri = docUri.toString(),
                                displayName = name,
                                sizeBytes = size,
                                modifiedAtMillis = modified,
                                category = category,
                                fingerprint = fingerprint,
                                status = UploadStatus.PENDING,
                                destinationChannelId = channelId
                            )
                        )
                        queued++
                        onProgress(ScanProgress(scanned, queued, pathSoFar))
                    }
                }
            }
        }

        return ScanProgress(scanned, queued, "")
    }
}
