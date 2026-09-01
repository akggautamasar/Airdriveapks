package com.airdrive.backup.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BackupCategory {
    PHOTOS, VIDEOS, PDFS, WORD_EXCEL, AUDIO, CALL_RECORDINGS, OTHER_FILES
}

enum class UploadStatus {
    PENDING, UPLOADING, UPLOADED, FAILED, SKIPPED
}

/**
 * One tracked file on the device. [fingerprint] is what duplicate detection is keyed on
 * (see util/Fingerprint.kt) so renames/path changes don't cause a re-upload as long as
 * the underlying bytes match.
 */
@Entity(
    tableName = "file_records",
    indices = [
        Index(value = ["fingerprint"], unique = false),
        Index(value = ["uri"], unique = true),
        Index(value = ["status"])
    ]
)
data class FileRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val category: BackupCategory,
    val fingerprint: String,
    val status: UploadStatus = UploadStatus.PENDING,
    val destinationChannelId: Long = 0,
    val telegramMessageId: Long? = null,
    val addedAtMillis: Long = System.currentTimeMillis(),
    val uploadedAtMillis: Long? = null,
    val retryCount: Int = 0,
    val lastError: String? = null
)
