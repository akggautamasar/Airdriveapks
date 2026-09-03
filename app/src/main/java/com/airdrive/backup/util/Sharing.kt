package com.airdrive.backup.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Share" and "Open with" for a file AirDrive knows about.
 *
 * Everything is shared out of the app's own cache rather than from wherever the file actually
 * lives. That keeps the FileProvider roots narrow (see res/xml/file_paths.xml) and means a SAF
 * document — which has no filesystem path at all — shares exactly like a local one. The staging
 * copy is small in practice because sharing is a one-file-at-a-time user action.
 */
object Sharing {

    private const val TAG = "AirDrive.Share"
    private const val STAGING_DIR = "share_staging"

    /** Keeps the staging folder from growing without bound across a long session. */
    private const val MAX_STAGED_FILES = 20

    fun mimeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /**
     * Copies [source] into the shareable cache and returns a content:// URI for it, or null if the
     * bytes could not be read. [displayName] is what the receiving app will show.
     */
    suspend fun stage(context: Context, source: Uri, displayName: String): Uri? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, STAGING_DIR).apply { mkdirs() }
                prune(dir)
                val target = File(dir, displayName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                    .ifBlank { "shared" })
                if (source.scheme.equals("file", ignoreCase = true)) {
                    val path = source.path ?: return@runCatching null
                    File(path).inputStream().use { input ->
                        target.outputStream().use { input.copyTo(it, bufferSize = 1 shl 20) }
                    }
                } else {
                    context.contentResolver.openInputStream(source)?.use { input ->
                        target.outputStream().use { input.copyTo(it, bufferSize = 1 shl 20) }
                    } ?: return@runCatching null
                }
                uriFor(context, target)
            }.onFailure { Log.w(TAG, "could not stage $displayName: ${it.message}") }.getOrNull()
        }

    /** A content:// URI for a file already inside one of the declared provider roots. */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Fires the system share sheet. Returns false if nothing on the device can handle it. */
    fun share(context: Context, uri: Uri, fileName: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(fileName)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return launch(context, Intent.createChooser(intent, "Share $fileName"))
    }

    /** Opens the file in whichever app claims its type. Returns false if none does. */
    fun open(context: Context, uri: Uri, fileName: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(fileName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return launch(context, intent)
    }

    private fun launch(context: Context, intent: Intent): Boolean = try {
        // The screens calling this are Activity-hosted, but the Context handed down by Compose is
        // not always an Activity, so the flag is required rather than optional.
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Log.w(TAG, "no app could handle the intent: ${e.message}")
        false
    }

    /** Oldest-first deletion once the staging folder has more than [MAX_STAGED_FILES] entries. */
    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size < MAX_STAGED_FILES) return
        files.take(files.size - MAX_STAGED_FILES + 1).forEach { runCatching { it.delete() } }
    }
}
