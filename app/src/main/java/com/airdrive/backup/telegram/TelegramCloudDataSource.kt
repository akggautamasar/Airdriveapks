package com.airdrive.backup.telegram

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
    private var closed = false

    override fun open(dataSpec: DataSpec): Long {
        if (closed) throw IOException("Data source is already closed")
        position = dataSpec.position
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            knownLength > 0L -> (knownLength - position).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened) throw IOException("Data source is not open")
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT

        val requested = minOf(length, 512 * 1024)
        val bytes = try {
            runBlocking(Dispatchers.IO) {
                tdClient.downloadFileRange(chatId, messageId, position, requested)
            }
        } catch (t: Throwable) {
            throw IOException("Telegram stream failed at byte $position", t)
        }
        if (bytes.isEmpty()) return C.RESULT_END_OF_INPUT

        val count = minOf(bytes.size, length)
        System.arraycopy(bytes, 0, buffer, offset, count)
        position += count
        if (remaining != C.LENGTH_UNSET.toLong()) remaining = (remaining - count).coerceAtLeast(0L)
        return count
    }

    override fun getUri() = null

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        opened = false
        closed = true
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
