package com.airdrive.backup.drive

import android.media.MediaDataSource
import com.airdrive.backup.telegram.TdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Random-access reader over one file that lives in Telegram. Only the bytes that are asked for
 * are fetched (2 MiB aligned chunks, through the same [TdClient.downloadFileRange] the in-app
 * viewer uses), recent chunks are kept in a small in-memory LRU, and the chunk after the one
 * being read is fetched in the background so sequential playback does not stall at boundaries.
 *
 * TDLib serves one range per file at a time, so every network fetch for this file goes through
 * [fetchLock]; the background prefetch takes the same lock and can never overlap a foreground read.
 */
class CloudRangeReader(
    private val client: TdClient,
    val chatId: Long,
    val messageId: Long,
    val totalSize: Long
) {
    companion object {
        const val CHUNK = 2 * 1024 * 1024
        private const val MAX_CHUNKS = 12
        private val prefetchPool: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "airdrive-prefetch").apply { isDaemon = true }
        }
    }

    private val cacheLock = Any()
    private val fetchLock = Any()
    private val cache = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            this.size > MAX_CHUNKS
    }
    @Volatile private var closed = false
    private var prefetching = -1L

    /** Copies up to [length] bytes starting at [position]. Returns 0 at end of file. */
    @Throws(IOException::class)
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0 || position < 0L || position >= totalSize) return 0
        val end = minOf(totalSize, position + length)
        var pos = position
        var copied = 0
        while (pos < end) {
            val index = pos / CHUNK
            val chunk = chunk(index)
            val inChunk = (pos - index * CHUNK).toInt()
            val n = minOf(chunk.size - inChunk, (end - pos).toInt())
            if (n <= 0) throw IOException("Short chunk at byte $pos")
            System.arraycopy(chunk, inChunk, buffer, offset + copied, n)
            copied += n
            pos += n
        }
        prefetchAfter((end - 1) / CHUNK)
        return copied
    }

    fun close() {
        closed = true
        synchronized(cacheLock) { cache.clear() }
    }

    private fun chunk(index: Long): ByteArray {
        synchronized(cacheLock) { cache[index] }?.let { return it }
        return synchronized(fetchLock) {
            // A prefetch may have fetched it while we were waiting for the lock.
            val again = synchronized(cacheLock) { cache[index] }
            if (again != null) {
                again
            } else {
                if (closed) throw IOException("Reader closed")
                val data = fetch(index)
                synchronized(cacheLock) { cache[index] = data }
                data
            }
        }
    }

    private fun fetch(index: Long): ByteArray {
        val start = index * CHUNK
        val want = minOf(CHUNK.toLong(), totalSize - start).toInt()
        if (want <= 0) throw IOException("Beyond end of file")
        val out = ByteArray(want)
        var got = 0
        var failures = 0
        while (got < want) {
            val part = try {
                runBlocking(Dispatchers.IO) { client.downloadFileRange(chatId, messageId, start + got, want - got) }
            } catch (e: Exception) {
                if (++failures > 2) throw IOException("Telegram stream failed at byte ${start + got}", e)
                Thread.sleep(500L * failures)
                continue
            }
            if (part.isEmpty()) {
                if (++failures > 2) throw IOException("Telegram returned no data at byte ${start + got}")
                Thread.sleep(400L * failures)
                continue
            }
            val n = minOf(part.size, want - got)
            System.arraycopy(part, 0, out, got, n)
            got += n
        }
        return out
    }

    private fun prefetchAfter(lastReadChunk: Long) {
        val next = lastReadChunk + 1
        if (next * CHUNK >= totalSize) return
        synchronized(cacheLock) {
            if (closed || prefetching == next || cache.containsKey(next)) return
            prefetching = next
        }
        prefetchPool.execute {
            try {
                synchronized(fetchLock) {
                    val have = synchronized(cacheLock) { cache.containsKey(next) }
                    if (!closed && !have) {
                        val data = fetch(next)
                        synchronized(cacheLock) { cache[next] = data }
                    }
                }
            } catch (_: Exception) {
                // Best effort: a foreground read will fetch it (and report errors) if needed.
            } finally {
                synchronized(cacheLock) { if (prefetching == next) prefetching = -1L }
            }
        }
    }
}

/** Lets MediaMetadataRetriever pull video frames for thumbnails without downloading the file. */
class CloudMediaSource(private val reader: CloudRangeReader) : MediaDataSource() {
    override fun getSize(): Long = reader.totalSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= reader.totalSize) return -1
        val n = reader.read(position, buffer, offset, size)
        return if (n > 0) n else -1
    }

    override fun close() {}
}
