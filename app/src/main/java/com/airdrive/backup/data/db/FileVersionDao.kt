package com.airdrive.backup.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * How many copies of one file are on record. Projected in a single grouped query rather than counted
 * per row, because the history list is hundreds of files long and a flow per row would mean hundreds
 * of live queries for a number that fits in one.
 */
data class VersionCount(val recordId: Long, val versions: Int)

@Dao
interface FileVersionDao {

    /**
     * REPLACE rather than plain insert: the unique (recordId, revision) index means a retry whose
     * first attempt actually reached Telegram overwrites its own half-written history row instead of
     * adding a second entry for identical bytes.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(version: FileVersion): Long

    /** Newest first, which is the order the history is read in. */
    @Query("SELECT * FROM file_versions WHERE recordId = :recordId ORDER BY revision DESC")
    fun versionsFlow(recordId: Long): Flow<List<FileVersion>>

    @Query("SELECT * FROM file_versions WHERE recordId = :recordId ORDER BY revision DESC")
    suspend fun versions(recordId: Long): List<FileVersion>

    @Query("SELECT COUNT(*) FROM file_versions WHERE recordId = :recordId")
    fun versionCountFlow(recordId: Long): Flow<Int>

    /** Per-file version counts for the whole history list, in one query. */
    @Query(
        "SELECT recordId, COUNT(*) AS versions FROM file_versions " +
        "GROUP BY recordId HAVING COUNT(*) > 1"
    )
    fun versionCountsFlow(): Flow<List<VersionCount>>

    /** How many files have more than one recorded version — the dashboard badge. */
    @Query(
        "SELECT COUNT(*) FROM (SELECT recordId FROM file_versions " +
        "GROUP BY recordId HAVING COUNT(*) > 1)"
    )
    fun versionedFileCountFlow(): Flow<Int>

    /**
     * Files with a real history, most recently changed first. Driven by the version table rather
     * than by file_records.revision, because a revision counter says a file changed while this says
     * an older copy is still reachable — and only the second one is worth offering to restore.
     */
    @Query(
        "SELECT * FROM file_records WHERE id IN " +
        "(SELECT recordId FROM file_versions GROUP BY recordId HAVING COUNT(*) > 1) " +
        "ORDER BY COALESCE(uploadedAtMillis, addedAtMillis) DESC LIMIT :limit"
    )
    fun versionedFilesFlow(limit: Int): Flow<List<FileRecord>>

    @Query("DELETE FROM file_versions WHERE recordId = :recordId")
    suspend fun deleteForRecord(recordId: Long): Int

    /**
     * Rows whose file_records row has gone. Cheaper than a foreign key and impossible to get wrong
     * at migration time; called once per backup run, so the table stays tidy without ceremony.
     */
    @Query("DELETE FROM file_versions WHERE recordId NOT IN (SELECT id FROM file_records)")
    suspend fun deleteOrphans(): Int
}
