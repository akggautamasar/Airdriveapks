package com.airdrive.backup.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Produces a stable identity for a file without hashing the whole thing, which would be
 * prohibitively slow for large videos. Strategy: size + a SHA-256 over the first 256KB and
 * the last 256KB of the file (or the whole file if smaller than 512KB). Two files with the
 * same size whose head and tail bytes match are treated as the same file for backup purposes,
 * which is what lets AirDrive recognize an already-backed-up file even after a rename.
 *
 * The byte-for-byte behaviour is shared by both entry points on purpose: fingerprints written
 * by the old SAF-only scanner must still match the ones computed from a direct file path, or
 * every already-uploaded file would look new after the switch to whole-device scanning.
 */
object Fingerprint {
    private const val CHUNK = 256 * 1024L

    fun compute(context: Context, uri: Uri, sizeBytes: Long): String =
        digest(sizeBytes) { context.contentResolver.openInputStream(uri) }

    fun compute(file: File, sizeBytes: Long = file.length()): String =
        digest(sizeBytes) { file.inputStream() }

    private fun digest(sizeBytes: Long, openStream: () -> InputStream?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sizeBytes.toString().toByteArray())

        openStream()?.use { input ->
            if (sizeBytes <= CHUNK * 2) {
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            } else {
                val head = ByteArray(CHUNK.toInt())
                var readTotal = 0
                while (readTotal < head.size) {
                    val r = input.read(head, readTotal, head.size - readTotal)
                    if (r == -1) break
                    readTotal += r
                }
                digest.update(head, 0, readTotal)

                val toSkip = sizeBytes - CHUNK * 2 - readTotal
                var skipped = 0L
                while (skipped < toSkip) {
                    val s = input.skip(toSkip - skipped)
                    if (s <= 0) break
                    skipped += s
                }

                val tail = ByteArray(CHUNK.toInt())
                var tailRead = 0
                while (tailRead < tail.size) {
                    val r = input.read(tail, tailRead, tail.size - tailRead)
                    if (r == -1) break
                    tailRead += r
                }
                digest.update(tail, 0, tailRead)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
