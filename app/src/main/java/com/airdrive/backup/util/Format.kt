package com.airdrive.backup.util

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Shared formatting. An object rather than top-level functions so it can be used from workers,
 * notifications and Compose screens without colliding with the screen-local helpers that were
 * already there.
 */
object Format {

    fun bytes(bytes: Long): String {
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        if (gb >= 1) return String.format(Locale.US, "%.1f GB", gb)
        val mb = bytes / 1024.0 / 1024.0
        if (mb >= 1) return String.format(Locale.US, "%.1f MB", mb)
        val kb = bytes / 1024.0
        if (kb >= 1) return String.format(Locale.US, "%.0f KB", kb)
        return "$bytes B"
    }

    /**
     * Media length the way a video player writes it: 01:42:32, or 4:07 when it is under an hour.
     * Used for the duration badge on video thumbnails.
     */
    fun duration(millis: Long): String {
        if (millis <= 0L) return ""
        val totalSeconds = millis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** How long something took, in words: "4m 12s". For run durations, not media length. */
    fun elapsed(millis: Long): String {
        if (millis <= 0L) return "0s"
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60L
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60L
        return when {
            hours > 0L -> "${hours}h ${minutes}m"
            minutes > 0L -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /** Thousands separators, so 10000 reads as "10,000" in the incremental summary. */
    fun count(value: Int): String = String.format(Locale.US, "%,d", value)
}
