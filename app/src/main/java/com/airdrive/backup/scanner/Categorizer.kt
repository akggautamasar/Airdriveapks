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
        ".thumbnails", ".cache", "cache", ".trash", "trash", "thumbnails", ".temp", "temp"
    )
    val SKIP_PATH_SUBSTRINGS = listOf("android/data/com.android")

    fun isSkippedDir(name: String, fullPathLower: String): Boolean {
        if (SKIP_DIR_NAMES.contains(name.lowercase())) return true
        return SKIP_PATH_SUBSTRINGS.any { fullPathLower.contains(it) }
    }

    fun categorize(fileNameLower: String, pathLower: String): BackupCategory {
        if (CALL_RECORDING_PATH_HINTS.any { pathLower.contains(it) }) {
            return BackupCategory.CALL_RECORDINGS
        }
        val ext = fileNameLower.substringAfterLast('.', missingDelimiterValue = "")
        return extensionMap[ext] ?: BackupCategory.OTHER_FILES
    }
}
