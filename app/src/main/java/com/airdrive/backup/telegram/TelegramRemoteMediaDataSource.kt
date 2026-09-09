package com.airdrive.backup.telegram

import android.media.MediaDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import kotlin.math.min

/**
 * A seekable media source backed by Telegram byte ranges. The player asks only for the bytes it
 * currently needs, so a cloud-only video/audio file is not copied to the user's storage.
 */
class TelegramRemoteMediaDataSource(
    private val client: TdClient,
    private val chatId: Long,
    private val messageId: Long,
    private val totalSize: Long
) : MediaDataSource() {
    private val lock = Any()
    private var cacheStart = -1L
    private var cache = ByteArray(0)
    private val chunkSize = 1024 * 1024

    override fun getSize(): Long = totalSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0L || position >= totalSize) return -1
        val requested = min(size.toLong(), totalSize - position).toInt()
        var copied = 0
        while (copied < requested) {
            val at = position + copied
            val available = synchronized(lock) {
                if (at >= cacheStart && at < cacheStart + cache.size) {
                    val inCache = (cacheStart + cache.size - at).toInt()
                    min(inCache, requested - copied)
                } else 0
            }
            if (available > 0) {
                synchronized(lock) {
                    val sourceOffset = (at - cacheStart).toInt()
                    cache.copyInto(buffer, offset + copied, sourceOffset, sourceOffset + available)
                }
                copied += available
                continue
            }

            val start = (at / chunkSize) * chunkSize
            val length = min(chunkSize.toLong(), totalSize - start).toInt()
            val bytes = runBlocking(Dispatchers.IO) {
                client.downloadFileRange(chatId, messageId, start, length)
            }
            if (bytes.isEmpty()) break
            synchronized(lock) {
                cacheStart = start
                cache = bytes
            }
        }
        return copied.takeIf { it > 0 } ?: -1
    }

    override fun close() {
        synchronized(lock) {
            cacheStart = -1L
            cache = ByteArray(0)
        }
    }
}

/** Sequential stream adapter used by image/text/EPUB previews. */
class TelegramRemoteInputStream(
    private val client: TdClient,
    private val chatId: Long,
    private val messageId: Long,
    private val totalSize: Long
) : InputStream() {
    private var position = 0L
    private val chunkSize = 512 * 1024
    private var cacheStart = -1L
    private var cache = ByteArray(0)

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xff else -1
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= totalSize) return -1
        val wanted = min(length.toLong(), totalSize - position).toInt()
        var copied = 0
        while (copied < wanted) {
            if (position < cacheStart || position >= cacheStart + cache.size) {
                val start = (position / chunkSize) * chunkSize
                val amount = min(chunkSize.toLong(), totalSize - start).toInt()
                cache = runBlocking(Dispatchers.IO) { client.downloadFileRange(chatId, messageId, start, amount) }
                cacheStart = start
                if (cache.isEmpty()) break
            }
            val fromCache = (position - cacheStart).toInt()
            val count = min(cache.size - fromCache, wanted - copied)
            cache.copyInto(buffer, offset + copied, fromCache, fromCache + count)
            position += count
            copied += count
        }
        return copied.takeIf { it > 0 } ?: -1
    }

    override fun skip(n: Long): Long {
        val moved = min(n.coerceAtLeast(0L), totalSize - position)
        position += moved
        return moved
    }

    override fun available(): Int = min(Int.MAX_VALUE.toLong(), totalSize - position).toInt()
}
