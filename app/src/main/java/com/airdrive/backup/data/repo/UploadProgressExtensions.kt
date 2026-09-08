package com.airdrive.backup.data.repo

/**
 * Fraction of the currently uploading file, derived from the live byte counters.
 * Kept as an extension so the progress model remains backwards-compatible.
 */
val UploadProgress.currentFileFraction: Float
    get() = if (currentFileBytes <= 0L) {
        0f
    } else {
        (currentFileUploadedBytes.toDouble() / currentFileBytes)
            .toFloat()
            .coerceIn(0f, 1f)
    }
