package com.airdrive.backup.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupRunDao {

    @Insert
    suspend fun insert(run: BackupRun): Long

    @Update
    suspend fun update(run: BackupRun)

    @Query("SELECT * FROM backup_runs WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): BackupRun?

    @Query("SELECT * FROM backup_runs WHERE id = :id LIMIT 1")
    fun byIdFlow(id: Long): Flow<BackupRun?>

    @Query("SELECT * FROM backup_runs ORDER BY startedAtMillis DESC LIMIT :limit")
    fun recentRunsFlow(limit: Int): Flow<List<BackupRun>>

    @Query("SELECT * FROM backup_runs WHERE outcome != 'RUNNING' ORDER BY startedAtMillis DESC LIMIT 1")
    suspend fun lastFinishedRun(): BackupRun?

    @Query("SELECT COUNT(*) FROM backup_runs")
    fun runCountFlow(): Flow<Int>

    /**
     * Scan-side counters, written once the walk is over. Kept separate from the upload counters
     * so a crash between the two phases still leaves the "what changed" half intact.
     */
    @Query(
        "UPDATE backup_runs SET filesScanned = :scanned, filesNew = :newFiles, " +
        "filesModified = :modified, filesMissing = :missing, filesRenamed = :renamed WHERE id = :id"
    )
    suspend fun setScanCounts(id: Long, scanned: Int, newFiles: Int, modified: Int, missing: Int, renamed: Int)

    /** Live progress, cheap enough to call after every file. */
    @Query(
        "UPDATE backup_runs SET filesUploaded = :uploaded, filesFailed = :failed, " +
        "bytesUploaded = :bytes WHERE id = :id"
    )
    suspend fun setUploadCounts(id: Long, uploaded: Int, failed: Int, bytes: Long)

    @Query(
        "UPDATE backup_runs SET outcome = :outcome, finishedAtMillis = :finishedAt, note = :note " +
        "WHERE id = :id"
    )
    suspend fun finish(id: Long, outcome: RunOutcome, finishedAt: Long, note: String?)

    /**
     * A run row left RUNNING belongs to a process that was killed (low memory, force stop,
     * reboot). Called at startup so the timeline never shows a run that is stuck "in progress"
     * forever.
     */
    @Query(
        "UPDATE backup_runs SET outcome = 'CANCELLED', finishedAtMillis = :now, " +
        "note = COALESCE(note, 'Interrupted') WHERE outcome = 'RUNNING' AND id != :exceptId"
    )
    suspend fun closeStaleRuns(now: Long, exceptId: Long = -1L): Int

    /** Keeps the timeline from growing without bound; called after each run. */
    @Query(
        "DELETE FROM backup_runs WHERE id NOT IN " +
        "(SELECT id FROM backup_runs ORDER BY startedAtMillis DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int): Int
}
