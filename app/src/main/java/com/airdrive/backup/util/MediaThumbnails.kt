package com.airdrive.backup.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thumbnails and durations for the gallery, wishlist items 6 and 7.
 *
 * AirDrive deliberately ships no image-loading library, so this is the whole of it: decode at a
 * sampled size, keep the result in a small memory cache, and never touch the main thread. A grid
 * of 4 GB videos must not turn into a grid of 4 GB allocations, which is what [SAMPLE_TARGET_PX]
 * and the [LruCache] byte budget are for.
 *
 * Everything here fails soft. A thumbnail that cannot be produced is null, and the caller draws a
 * category icon instead — a missing preview is a cosmetic problem, not an error worth surfacing.
 */
object MediaThumbnails {

    /** Roughly the widest a grid cell ever is on a phone; decoding larger is wasted work. */
    private const val SAMPLE_TARGET_PX = 256

    /**
     * Preview decode target: enough to fill a dialog on a phone screen without decoding a
     * 108-megapixel photo at full size. Previews are deliberately never cached — one decode per
     * tap is cheap, and a handful of megabyte-sized bitmaps would evict every grid thumbnail.
     */
    private const val PREVIEW_TARGET_PX = 1024

    /** Videos are decoded through MediaMetadataRetriever, which is slow — 2 MB of cache pays off. */
    private const val CACHE_BYTES = 12 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Files that could not be decoded once will not decode next frame either; stop retrying. */
    private val failed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    private const val TAG = "AirDrive.Thumbs"

    /** Cached bitmap for [record], if one has already been decoded. Safe on the main thread. */
    fun peek(record: FileRecord): Bitmap? = cache.get(record.uri)

    /**
     * Decodes a thumbnail for [record], or returns null if it cannot be done — a SAF document with
     * no readable path, a file already deleted from the phone, or a format the platform decoder
     * does not understand.
     */
    suspend fun load(context: Context, record: FileRecord): Bitmap? {
        cache.get(record.uri)?.let { return it }
        if (record.uri in failed) return null
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { decode(context, record, SAMPLE_TARGET_PX) }
                .onFailure { Log.d(TAG, "no thumbnail for ${record.displayName}: ${it.message}") }
                .getOrNull()
        }
        if (bitmap == null) {
            failed.add(record.uri)
            return null
        }
        cache.put(record.uri, bitmap)
        return bitmap
    }

    /**
     * A larger decode for the tap-to-preview dialog. Not cached and not recorded in [failed], so a
     * preview that fails still leaves the grid thumbnail alone.
     */
    suspend fun loadPreview(context: Context, record: FileRecord): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching { decode(context, record, PREVIEW_TARGET_PX) }
                .onFailure { Log.d(TAG, "no preview for ${record.displayName}: ${it.message}") }
                .getOrNull()
        }

    private fun decode(context: Context, record: FileRecord, targetPx: Int): Bitmap? {
        val path = localPath(record)
        return when (record.category) {
            BackupCategory.PHOTOS -> path?.let { decodeSampledImage(it, targetPx) }
                ?: decodeSampledImageFromStream(context, record.uri, targetPx)
            BackupCategory.VIDEOS -> path?.let { decodeVideoFrame(it, targetPx) }
            else -> null
        }
    }


    /**
     * The real filesystem path behind a record, or null for a SAF document. Files restored from
     * the Telegram manifest carry a "restored://" URI and have no local bytes at all.
     */
    private fun localPath(record: FileRecord): String? {
        val uri = runCatching { Uri.parse(record.uri) }.getOrNull() ?: return null
        if (!uri.scheme.equals("file", ignoreCase = true)) return null
        val path = uri.path ?: return null
        val file = File(path)
        return if (file.isFile && file.canRead()) path else null
    }

    /**
     * Two-pass decode: measure with inJustDecodeBounds, then decode at a power-of-two sample size.
     * Decoding a 108-megapixel photo at full size would OOM long before the grid finished loading.
     */
    private fun decodeSampledImage(path: String, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    /** SAF documents have no path, so they are read through the content resolver instead. */
    private fun decodeSampledImageFromStream(
        context: Context,
        uriString: String,
        targetPx: Int
    ): Bitmap? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        if (uri.scheme.equals("restored", ignoreCase = true)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / (sample * 2) >= targetPx) sample *= 2
        return sample
    }

    /**
     * A frame from one second in, which avoids the black opening frame most cameras record, then
     * shrunk to the requested size. Scaling keeps the aspect ratio — the grid squares its own cells
     * with ContentScale.Crop, and the preview dialog wants the real shape.
     *
     * MediaMetadataRetriever must be released or it leaks a native decoder.
     */
    private fun decodeVideoFrame(path: String, targetPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val frame = retriever.getFrameAtTime(1_000_000L)
                ?: retriever.getFrameAtTime(0L)
                ?: return null
            val longest = maxOf(frame.width, frame.height)
            if (longest <= targetPx) {
                frame
            } else {
                val scale = targetPx.toFloat() / longest
                val width = (frame.width * scale).toInt().coerceAtLeast(1)
                val height = (frame.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(frame, width, height, true)
                if (scaled !== frame) frame.recycle()
                scaled
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Video/audio length in milliseconds, or null. Extracting this costs a native decoder open, so
     * the caller is expected to persist the answer (FileRecord.durationMillis) and never ask twice.
     */
    suspend fun duration(record: FileRecord): Long? = withContext(Dispatchers.IO) {
        val path = localPath(record) ?: return@withContext null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Called when the gallery leaves the screen; the grid can rebuild its previews cheaply. */
    fun trim() {
        cache.trimToSize(CACHE_BYTES / 4)
    }
}
