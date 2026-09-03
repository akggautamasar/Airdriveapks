package com.airdrive.backup.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One upload of one file — the row that turns AirDrive's index into a history (wishlist item 3).
 *
 * A [FileRecord] only ever describes the *current* state of a file: change a document and upload it
 * again and the old message id is overwritten, even though the older copy is still sitting in
 * Telegram perfectly intact. This table keeps those pointers, so "the version from before I ruined
 * it" stays reachable instead of being lost to a column update.
 *
 * [recordId] is not a foreign key on purpose. Room would need the exact same constraint spelled out
 * in the hand-written migration to validate the schema at startup, and a mismatch there is a crash
 * on launch; orphaned rows, by contrast, are invisible and get swept up on the next backup run.
 * See BackupRepository.pruneOrphanVersions.
 *
 * The unique index on ([recordId], [revision]) is what makes a re-upload of the same revision — a
 * retry whose first attempt actually landed — correct the existing row instead of quietly adding a
 * second history entry for the same bytes.
 */
@Entity(
    tableName = "file_versions",
    indices = [
        Index(value = ["recordId", "revision"], unique = true),
        Index(value = ["uploadedAtMillis"])
    ]
)
data class FileVersion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** The [FileRecord] this version belongs to. */
    val recordId: Long,

    /** [FileRecord.revision] as it was when these bytes were sent: 1 for the first upload. */
    val revision: Int,

    /** The name at the time. A file that was renamed between versions shows both names. */
    val displayName: String,

    val sizeBytes: Long,

    /** The file's own last-modified stamp, which is what "the version from Tuesday" means. */
    val modifiedAtMillis: Long,

    /** Fingerprint of these bytes, so two versions can be told apart from a rename. */
    val fingerprint: String,

    /** Chat and message that hold this copy. Both are needed to download it again. */
    val chatId: Long,
    val telegramMessageId: Long?,

    val uploadedAtMillis: Long,

    /** The backup run that sent it, so a version can be traced back to the timeline. */
    val runId: Long? = null
)
