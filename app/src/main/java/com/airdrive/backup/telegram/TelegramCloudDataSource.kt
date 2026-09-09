package com.airdrive.backup.telegram

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Media3 byte-range source backed directly by TDLib's Telegram file cache. */
class TelegramCloudDataSource(
    private val tdClient: TdClient,
    private val chatId: Long,
    private val messageId: Long,
    private val knownLength: Long
) : BaseDataSource(false) {
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    private var currentUri: Uri? = null
    private var cacheStart = -1L
    private var cache = ByteArray(0)
    private val chunkSize = 1024 * 1024

    override fun open(dataSpec: DataSpec): Long {
        if (opened) throw IOException("Data source is already open")
        position = dataSpec.position.coerceAtLeast(0L)
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            knownLength > 0L -> (knownLength - position).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
        currentUri = dataSpec.uri
        cacheStart = -1L
        cache = ByteArray(0)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened) throw IOException("Data source is not open")
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT

        val wanted = minOf(length.toLong(), if (remaining == C.LENGTH_UNSET.toLong()) length.toLong() else remaining).toInt()
        var copied = 0
        while (copied < wanted) {
            val at = position
            val cached = if (at >= cacheStart && at < cacheStart + cache.size) {
                minOf(cache.size - (at - cacheStart).toInt(), wanted - copied)
            } else 0
            if (cached > 0) {
                val sourceOffset = (at - cacheStart).toInt()
                cache.copyInto(buffer, offset + copied, sourceOffset, sourceOffset + cached)
                position += cached
                copied += cached
                if (remaining != C.LENGTH_UNSET.toLong()) remaining = (remaining - cached).coerceAtLeast(0L)
                continue
            }

            val chunkStart = (at / chunkSize) * chunkSize
            val chunkLength = minOf(chunkSize.toLong(), (knownLength - chunkStart).coerceAtLeast(0L)).toInt()
            if (chunkLength <= 0) break
            val bytes = try {
                runBlocking(Dispatchers.IO) {
                    tdClient.downloadFileRange(chatId, messageId, chunkStart, chunkLength)
                }
            } catch (t: Throwable) {
                throw IOException("Telegram stream failed at byte $at", t)
            }
            if (bytes.isEmpty()) break
            cacheStart = chunkStart
            cache = bytes
        }

        if (copied == 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(copied)
        return copied
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        if (!opened) return
        opened = false
        currentUri = null
        cacheStart = -1L
        cache = ByteArray(0)
        transferEnded()
    }

    class Factory(
        private val tdClient: TdClient,
        private val chatId: Long,
        private val messageId: Long,
        private val knownLength: Long
    ) : androidx.media3.datasource.DataSource.Factory {
        override fun createDataSource(): TelegramCloudDataSource =
            TelegramCloudDataSource(tdClient, chatId, messageId, knownLength)
    }
}
