package com.airdrive.backup.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BackupCategory {
    PHOTOS, VIDEOS, PDFS, WORD_EXCEL, AUDIO, CALL_RECORDINGS, OTHER_FILES
}

enum class UploadStatus {
    PENDING, UPLOADING, UPLOADED, FAILED, SKIPPED, CANCELLED
}

/**
 * Whether the file still exists on the phone. This is what separates "backed up" from "still
 * here": a file can be safely UPLOADED and simultaneously gone from local storage.
 *
 * - PRESENT: seen on disk by the most recent scan that covered its folder.
 * - MISSING: it was there before and has vanished (deleted, moved to a card that is not
 *   mounted, or moved into a folder the scan does not cover). The Telegram copy is kept.
 * - FREED: AirDrive itself deleted the local copy from the Storage cleanup screen, after
 *   confirming the Telegram copy was good.
 * - UNKNOWN: rows rebuilt from the Telegram manifest after a reinstall — there is no local
 *   path to check, so they are neither present nor deleted.
 */
enum class LocalState { PRESENT, MISSING, FREED, UNKNOWN }

/** Result of re-checking an uploaded file against Telegram. Written by BackupRepository.verifyNow. */
enum class VerifyState { UNVERIFIED, VERIFIED, MISSING_REMOTE, SIZE_MISMATCH, UNCHECKABLE }

/**
 * One tracked file on the device. [fingerprint] is what duplicate detection is keyed on
 * (see util/Fingerprint.kt) so renames/path changes don't cause a re-upload as long as
 * the underlying bytes match.
 *
 * [sizeBytes] and [modifiedAtMillis] double as the incremental-scan snapshot: the scanner
 * compares them against the filesystem and only re-fingerprints (and re-queues) a file whose
 * size or timestamp actually moved. Do not overwrite them without also resetting [status],
 * or a changed file will be remembered as already backed up.
 */
@Entity(
    tableName = "file_records",
    indices = [
        Index(value = ["fingerprint"], unique = false),
        Index(value = ["uri"], unique = true),
        Index(value = ["status"]),
        Index(value = ["category"]),
        Index(value = ["localState"])
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
    val lastError: String? = null,

    // ------------------------------------------------------------------ added in schema v2

    /** Presence of the local copy; see [LocalState]. */
    val localState: LocalState = LocalState.PRESENT,

    /** When [localState] last changed away from PRESENT — the "deleted on" date in the UI. */
    val localStateAtMillis: Long? = null,

    /** Set by the user on the Deleted files screen: never auto-remove the Telegram copy. */
    val keepForever: Boolean = false,

    val verifyState: VerifyState = VerifyState.UNVERIFIED,
    val verifiedAtMillis: Long? = null,

    /** 1 for the first upload, incremented every time changed bytes are uploaded again. */
    val revision: Int = 1,

    /** The backup run that last uploaded this file, so a run can list exactly what it moved. */
    val lastRunId: Long? = null,

    /** Cached video/audio length in milliseconds, filled in lazily by the media browser. */
    val durationMillis: Long? = null,

    /** When this file was last pulled back down from Telegram onto this device. */
    val restoredAtMillis: Long? = null
)
