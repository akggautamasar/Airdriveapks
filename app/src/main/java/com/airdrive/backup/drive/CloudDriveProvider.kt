package com.airdrive.backup.drive

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import com.airdrive.backup.R
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.telegram.TdClient
import com.airdrive.backup.ui.screens.categoryLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * AirDrive as a storage location: it shows up in the system Files app (Samsung My Files included)
 * and in every app's file picker. Everything AirDrive has backed up appears under category
 * folders, and opening a file STREAMS it from Telegram — only the bytes the app actually reads
 * are fetched, so seeking in a video is instant and nothing is copied onto the phone first.
 * Files copied into the drive are queued through the normal backup pipeline (see [CloudInbox]).
 *
 * Read-only by design for existing files: deleting from Telegram stays an explicit, confirmed
 * action inside AirDrive (Deleted files screen), never a side effect of a Files-app gesture.
 *
 * Document ids: "root", "cat:<BackupCategory>", "f:<file_records.id>", "in:<token>" (new upload slot).
 */
class CloudDriveProvider : DocumentsProvider() {

    companion object {
        private const val ROOT_ID = "root"
        private const val THUMB = 320
        private const val MAX_PHOTO_THUMB_BYTES = 12L * 1024 * 1024
        private const val RELEASE_DELAY_MS = 60_000L

        private val ROOT_COLS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_ICON, Root.COLUMN_SUMMARY
        )
        private val DOC_COLS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE
        )

        fun authority(ctx: Context): String = ctx.packageName + ".clouddrive"

        /** Tells the Files app to re-read the list of storage locations (after the on/off switch changes). */
        fun notifyRootsChanged(ctx: Context) {
            ctx.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority(ctx)), null)
        }

        fun rootDocumentUri(ctx: Context): Uri = DocumentsContract.buildDocumentUri(authority(ctx), ROOT_ID)

        fun rootUri(ctx: Context): Uri = DocumentsContract.buildRootUri(authority(ctx), ROOT_ID)
    }

    private val ctx: Context get() = context!!
    private val auth: String get() = authority(ctx)
    private val dao by lazy { AppDatabase.get(ctx).fileRecordDao() }
    private val client by lazy { TdClient.get(ctx) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readers = ConcurrentHashMap<Long, CloudRangeReader>()
    private val openHandles = ConcurrentHashMap<Long, AtomicInteger>()
    private val thumbGate = Semaphore(2)

    override fun onCreate(): Boolean {
        // Keep open Files-app windows fresh as uploads finish.
        scope.launch {
            runCatching {
                AppDatabase.get(ctx).fileRecordDao().uploadedCountFlow().distinctUntilChanged().collect {
                    CloudInbox.sweep(ctx)
                    notifyListings()
                }
            }
        }
        return true
    }

    private fun notifyListings() {
        val resolver = ctx.contentResolver
        resolver.notifyChange(DocumentsContract.buildChildDocumentsUri(auth, ROOT_ID), null)
        BackupCategory.values().forEach {
            resolver.notifyChange(DocumentsContract.buildChildDocumentsUri(auth, "cat:${it.name}"), null)
        }
    }

    private fun requireOpen() {
        if (!CloudDrivePrefs.isOpen(ctx)) throw SecurityException("The AirDrive cloud drive is turned off")
    }

    // ------------------------------------------------------------------ browsing

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLS)
        if (!CloudDrivePrefs.isOpen(ctx)) return cursor // hidden: no storage location is offered
        cursor.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_SEARCH)
            .add(Root.COLUMN_TITLE, "AirDrive")
            .add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            .add(Root.COLUMN_SUMMARY, "Streams from your Telegram")
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        requireOpen()
        val cursor = MatrixCursor(projection ?: DOC_COLS)
        when {
            documentId == ROOT_ID -> addDir(cursor, ROOT_ID, "AirDrive")
            documentId.startsWith("cat:") -> {
                val category = categoryOf(documentId) ?: throw FileNotFoundException(documentId)
                addDir(cursor, documentId, categoryLabel(category))
            }
            documentId.startsWith("in:") -> {
                val file = CloudInbox.fileFor(ctx, documentId) ?: throw FileNotFoundException(documentId)
                cursor.newRow()
                    .add(Document.COLUMN_DOCUMENT_ID, documentId)
                    .add(Document.COLUMN_DISPLAY_NAME, file.name)
                    .add(Document.COLUMN_MIME_TYPE, mimeOf(file.name))
                    .add(Document.COLUMN_SIZE, file.length())
                    .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
                    .add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE)
            }
            documentId.startsWith("f:") -> addRecord(cursor, recordOf(documentId))
            else -> throw FileNotFoundException(documentId)
        }
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        requireOpen()
        val cursor = MatrixCursor(projection ?: DOC_COLS)
        cursor.setNotificationUri(ctx.contentResolver, DocumentsContract.buildChildDocumentsUri(auth, parentDocumentId))
        when {
            parentDocumentId == ROOT_ID -> BackupCategory.values().forEach {
                addDir(cursor, "cat:${it.name}", categoryLabel(it))
            }
            parentDocumentId.startsWith("cat:") -> {
                val category = categoryOf(parentDocumentId) ?: throw FileNotFoundException(parentDocumentId)
                scope.launch { CloudInbox.sweep(ctx) }
                runBlocking { dao.cloudFilesInCategory(category, CloudInbox.uriPrefix(ctx)) }
                    .forEach { addRecord(cursor, it) }
            }
            else -> throw FileNotFoundException(parentDocumentId)
        }
        return cursor
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        requireOpen()
        val cursor = MatrixCursor(projection ?: DOC_COLS)
        runBlocking { dao.cloudSearch(query.trim(), 200) }.forEach { addRecord(cursor, it) }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = try {
        requireOpen()
        when {
            parentDocumentId == ROOT_ID -> when {
                documentId.startsWith("cat:") -> categoryOf(documentId) != null
                documentId.startsWith("in:") -> CloudInbox.fileFor(ctx, documentId) != null
                documentId.startsWith("f:") -> { recordOf(documentId); true }
                else -> false
            }
            parentDocumentId.startsWith("cat:") && documentId.startsWith("f:") ->
                recordOf(documentId).category == categoryOf(parentDocumentId)
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    private fun addDir(cursor: MatrixCursor, id: String, name: String) {
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, name)
            .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            .add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
    }

    private fun addRecord(cursor: MatrixCursor, rec: FileRecord) {
        val thumbs = rec.category == BackupCategory.PHOTOS || rec.category == BackupCategory.VIDEOS
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, "f:${rec.id}")
            .add(Document.COLUMN_DISPLAY_NAME, rec.displayName)
            .add(Document.COLUMN_MIME_TYPE, mimeOf(rec.displayName))
            .add(Document.COLUMN_SIZE, rec.sizeBytes)
            .add(Document.COLUMN_LAST_MODIFIED, rec.modifiedAtMillis)
            .add(Document.COLUMN_FLAGS, if (thumbs) Document.FLAG_SUPPORTS_THUMBNAIL else 0)
    }

    private fun categoryOf(docId: String): BackupCategory? =
        runCatching { BackupCategory.valueOf(docId.removePrefix("cat:")) }.getOrNull()

    private fun recordOf(docId: String): FileRecord {
        val id = docId.removePrefix("f:").toLongOrNull() ?: throw FileNotFoundException(docId)
        return runBlocking { dao.cloudById(id) } ?: throw FileNotFoundException(docId)
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    // ------------------------------------------------------------------ reading (streaming)

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        requireOpen()
        if (documentId.startsWith("in:")) return openStagedSlot(documentId, mode)
        if (mode.any { it == 'w' || it == 'a' }) throw SecurityException("Files already in AirDrive are read-only here")

        val rec = recordOf(documentId)
        // The original on this phone (or a staged copy still waiting to upload) is instant and works offline.
        localOriginal(rec)?.let { return ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }

        val messageId = rec.telegramMessageId
        if (rec.status != UploadStatus.UPLOADED || messageId == null || rec.destinationChannelId == 0L) {
            throw FileNotFoundException("This file has not finished uploading to Telegram yet")
        }
        if (!runBlocking { client.awaitReady(20_000) }) throw FileNotFoundException("AirDrive is not signed in to Telegram")

        val reader = readers.getOrPut(rec.id) { CloudRangeReader(client, rec.destinationChannelId, messageId, rec.sizeBytes) }
        openHandles.getOrPut(rec.id) { AtomicInteger() }.incrementAndGet()
        val thread = HandlerThread("airdrive-cloud-read").also { it.start() }
        val storage = ctx.getSystemService(StorageManager::class.java)
        return storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            StreamCallback(reader) { onHandleClosed(rec, thread) },
            Handler(thread.looper)
        )
    }

    private fun localOriginal(rec: FileRecord): File? {
        val uri = Uri.parse(rec.uri)
        if (!uri.scheme.equals("file", ignoreCase = true)) return null
        val file = File(uri.path ?: return null)
        return file.takeIf { it.isFile && it.canRead() && it.length() == rec.sizeBytes }
    }

    private fun onHandleClosed(rec: FileRecord, thread: HandlerThread) {
        thread.quitSafely()
        val left = openHandles[rec.id]?.decrementAndGet() ?: 0
        if (left <= 0) {
            openHandles.remove(rec.id)
            readers.remove(rec.id)?.close()
            scheduleRelease(rec)
        }
    }

    /** Shortly after the last app lets go of a file, drop Telegram's cached copy so streaming leaves no residue. */
    private fun scheduleRelease(rec: FileRecord) {
        val messageId = rec.telegramMessageId ?: return
        if (rec.destinationChannelId == 0L || !CloudDrivePrefs.freeCacheOnClose(ctx)) return
        scope.launch {
            delay(RELEASE_DELAY_MS)
            if ((openHandles[rec.id]?.get() ?: 0) == 0) {
                runCatching { client.releaseCachedFile(rec.destinationChannelId, messageId) }
            }
        }
    }

    private class StreamCallback(
        private val reader: CloudRangeReader,
        private val onClosed: () -> Unit
    ) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = reader.totalSize

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
            reader.read(offset, data, 0, size)
        } catch (e: Exception) {
            throw ErrnoException("onRead", OsConstants.EIO)
        }

        override fun onRelease() {
            onClosed()
        }
    }

    // ------------------------------------------------------------------ writing (copy in)

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        requireOpen()
        if (parentDocumentId != ROOT_ID && categoryOf(parentDocumentId) == null) {
            throw FileNotFoundException(parentDocumentId)
        }
        if (mimeType == Document.MIME_TYPE_DIR) throw UnsupportedOperationException("AirDrive sorts files into folders by type automatically")
        return CloudInbox.newSlot(ctx, displayName).first
    }

    private fun openStagedSlot(documentId: String, mode: String): ParcelFileDescriptor {
        val file = CloudInbox.fileFor(ctx, documentId) ?: throw FileNotFoundException(documentId)
        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        if (!mode.contains('w')) return ParcelFileDescriptor.open(file, pfdMode)
        return ParcelFileDescriptor.open(file, pfdMode, Handler(Looper.getMainLooper())) { error ->
            if (error == null) scope.launch { CloudInbox.commit(ctx, file) }
        }
    }

    override fun deleteDocument(documentId: String) {
        requireOpen()
        if (documentId.startsWith("in:")) {
            CloudInbox.fileFor(ctx, documentId)?.let { runBlocking { CloudInbox.discardWithRecord(ctx, it) } }
            return
        }
        throw UnsupportedOperationException("Deleting is not available here. Use AirDrive > Deleted files, which asks first.")
    }

    // ------------------------------------------------------------------ thumbnails

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor {
        requireOpen()
        if (!documentId.startsWith("f:")) throw FileNotFoundException(documentId)
        val rec = recordOf(documentId)
        val dir = File(ctx.cacheDir, "cloud-thumbs").apply { mkdirs() }
        val cached = File(dir, "${rec.id}-${rec.sizeBytes}.jpg")
        if (!cached.isFile) {
            thumbGate.acquire() // the Files app asks for a whole screen of thumbnails at once; keep it to two at a time
            try {
                if (!cached.isFile) buildThumbnail(rec, cached)
            } finally {
                thumbGate.release()
            }
        }
        if (!cached.isFile) throw FileNotFoundException("No thumbnail")
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH
        )
    }

    private fun buildThumbnail(rec: FileRecord, out: File) {
        val bitmap = when (rec.category) {
            BackupCategory.PHOTOS -> photoThumb(rec)
            BackupCategory.VIDEOS -> videoThumb(rec)
            else -> null
        } ?: return
        val tmp = File(out.path + ".tmp")
        runCatching {
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            tmp.renameTo(out)
        }
        if (tmp.exists()) tmp.delete()
    }

    private fun photoThumb(rec: FileRecord): Bitmap? {
        val local = localOriginal(rec)
        val bitmap = if (local != null) {
            decodeSampled { o -> BitmapFactory.decodeFile(local.absolutePath, o) }
        } else {
            if (rec.sizeBytes <= 0L || rec.sizeBytes > MAX_PHOTO_THUMB_BYTES) return null
            val bytes = readAllFromCloud(rec) ?: return null
            decodeSampled { o -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) }
        }
        return bitmap?.let { scaleDown(it) }
    }

    private fun decodeSampled(decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB && bounds.outHeight / (sample * 2) >= THUMB) sample *= 2
        return decode(BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= THUMB) return bitmap
        val factor = THUMB.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * factor).toInt().coerceAtLeast(1), (bitmap.height * factor).toInt().coerceAtLeast(1), true
        )
    }

    private fun cloudReader(rec: FileRecord): CloudRangeReader? {
        val messageId = rec.telegramMessageId ?: return null
        if (rec.status != UploadStatus.UPLOADED || rec.destinationChannelId == 0L) return null
        if (!runBlocking { client.awaitReady(20_000) }) return null
        return CloudRangeReader(client, rec.destinationChannelId, messageId, rec.sizeBytes)
    }

    private fun readAllFromCloud(rec: FileRecord): ByteArray? {
        val reader = cloudReader(rec) ?: return null
        return try {
            val buf = ByteArray(rec.sizeBytes.toInt())
            var pos = 0
            while (pos < buf.size) {
                val n = reader.read(pos.toLong(), buf, pos, buf.size - pos)
                if (n <= 0) return null
                pos += n
            }
            buf
        } catch (e: Exception) {
            null
        } finally {
            reader.close()
            scheduleRelease(rec)
        }
    }

    private fun videoThumb(rec: FileRecord): Bitmap? {
        val retriever = MediaMetadataRetriever()
        var reader: CloudRangeReader? = null
        return try {
            val local = localOriginal(rec)
            if (local != null) {
                retriever.setDataSource(local.absolutePath)
            } else {
                val r = cloudReader(rec) ?: return null
                reader = r
                retriever.setDataSource(CloudMediaSource(r))
            }
            val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame?.let { scaleDown(it) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
            reader?.let {
                it.close()
                scheduleRelease(rec)
            }
        }
    }
}
