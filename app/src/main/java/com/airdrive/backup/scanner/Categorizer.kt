package com.airdrive.backup.scanner

import com.airdrive.backup.data.db.BackupCategory

object Categorizer {

    private val extensionMap: Map<String, BackupCategory> = buildMap {
        listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff", "tif", "raw", "heic", "svg", "ico")
            .forEach { put(it, BackupCategory.PHOTOS) }
        listOf("mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "m4v", "3gp", "mts", "ts", "vob", "f4v")
            .forEach { put(it, BackupCategory.VIDEOS) }
        put("pdf", BackupCategory.PDFS)
        listOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp")
            .forEach { put(it, BackupCategory.WORD_EXCEL) }
        listOf("mp3", "flac", "wav", "m4a", "ogg", "aac", "opus", "wma", "ape", "alac")
            .forEach { put(it, BackupCategory.AUDIO) }
    }

    /** Substrings identifying common manufacturer call-recording directories. */
    val CALL_RECORDING_PATH_HINTS = listOf(
        "recordings/call", "call recordings", "callrecordings", "callrecord"
    )

    val SKIP_DIR_NAMES = setOf(
        ".thumbnails", ".cache", "cache", ".trash", "trash", "thumbnails", ".temp", "temp",
        "lost.dir", ".spotlight-v100", ".fseventsd", ".estrongs", ".face", ".ustats",
        "tdlib", "tdlib-files"
    )

    /**
     * Paths that are either unreadable or not worth uploading. /Android/data and /Android/obb
     * are off limits on Android 11+ even with All files access, and are full of app caches
     * anyway. /Android/media is deliberately NOT here: that is where WhatsApp and similar apps
     * keep photos and videos on newer Android versions.
     */
    val SKIP_PATH_SUBSTRINGS = listOf("/android/data", "/android/obb")

    /** File names that are partial downloads or trash tombstones rather than real user files. */
    private val SKIP_FILE_EXTENSIONS = setOf("tmp", "temp", "part", "crdownload", "download", "!ut")

    fun isSkippedDir(name: String, fullPathLower: String): Boolean {
        val lower = name.lowercase()
        if (SKIP_DIR_NAMES.contains(lower)) return true
        // Hidden folders are caches, sync scratch space and app internals far more often than
        // they are user data.
        if (lower.startsWith(".")) return true
        return SKIP_PATH_SUBSTRINGS.any { fullPathLower.contains(it) }
    }

    /**
     * Empty files, hidden files and half-finished downloads are not worth a Telegram round trip.
     * Android's own trash/pending media also lands here (.trashed-*, .pending-*).
     */
    fun isSkippedFile(name: String, sizeBytes: Long): Boolean {
        if (sizeBytes <= 0L) return true
        val lower = name.lowercase()
        if (lower.startsWith(".")) return true
        return SKIP_FILE_EXTENSIONS.contains(lower.substringAfterLast('.', ""))
    }

    fun categorize(fileNameLower: String, pathLower: String): BackupCategory {
        if (CALL_RECORDING_PATH_HINTS.any { pathLower.contains(it) }) {
            return BackupCategory.CALL_RECORDINGS
        }
        val ext = fileNameLower.substringAfterLast('.', missingDelimiterValue = "")
        return extensionMap[ext] ?: BackupCategory.OTHER_FILES
    }
}
