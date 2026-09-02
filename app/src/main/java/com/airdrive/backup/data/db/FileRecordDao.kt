package com.airdrive.backup.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class CategoryCount(val category: BackupCategory, val count: Int, val bytes: Long)

/** Per-category totals for the Categories screen: everything queued, plus what is already up. */
data class CategoryTotals(
    val category: BackupCategory,
    val total: Int,
    val totalBytes: Long,
    val uploaded: Int,
    val uploadedBytes: Long
)

@Dao
interface FileRecordDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: FileRecord): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<FileRecord>): List<Long>

    @Update
    suspend fun update(record: FileRecord)

    @Query("SELECT * FROM file_records WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): FileRecord?

    @Query("SELECT * FROM file_records WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): FileRecord?

    @Query("SELECT * FROM file_records WHERE status = 'PENDING' ORDER BY addedAtMillis ASC")
    suspend fun pendingFiles(): List<FileRecord>

    @Query("SELECT * FROM file_records WHERE status = 'PENDING' ORDER BY addedAtMillis ASC LIMIT :limit")
    suspend fun nextPendingBatch(limit: Int): List<FileRecord>

    @Query("SELECT * FROM file_records WHERE status = 'FAILED' ORDER BY uploadedAtMillis DESC")
    fun failedFilesFlow(): Flow<List<FileRecord>>

    @Query("SELECT * FROM file_records WHERE status = 'UPLOADED' ORDER BY uploadedAtMillis DESC LIMIT :limit")
    fun recentUploadsFlow(limit: Int): Flow<List<FileRecord>>

    @Query("SELECT * FROM file_records ORDER BY addedAtMillis DESC LIMIT :limit")
    fun recentActivityFlow(limit: Int): Flow<List<FileRecord>>

    @Query("SELECT COUNT(*) FROM file_records WHERE status = 'UPLOADED'")
    fun uploadedCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM file_records WHERE status = 'PENDING'")
    fun pendingCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM file_records WHERE status = 'FAILED'")
    fun failedCountFlow(): Flow<Int>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM file_records WHERE status = 'UPLOADED'")
    fun uploadedBytesFlow(): Flow<Long>

    @Query("SELECT MAX(uploadedAtMillis) FROM file_records WHERE status = 'UPLOADED'")
    fun lastBackupTimeFlow(): Flow<Long?>

    @Query(
        "SELECT category, COUNT(*) as count, COALESCE(SUM(sizeBytes),0) as bytes " +
        "FROM file_records WHERE status = 'UPLOADED' GROUP BY category"
    )
    fun categoryBreakdownFlow(): Flow<List<CategoryCount>>

    /**
     * The Categories screen used to read categoryBreakdownFlow, which only counts UPLOADED
     * rows — so it showed 0 files everywhere while the queue was still working through 2500
     * pending files. This one reports both halves.
     */
    @Query(
        "SELECT category, " +
        "COUNT(*) as total, " +
        "COALESCE(SUM(sizeBytes),0) as totalBytes, " +
        "COALESCE(SUM(CASE WHEN status = 'UPLOADED' THEN 1 ELSE 0 END),0) as uploaded, " +
        "COALESCE(SUM(CASE WHEN status = 'UPLOADED' THEN sizeBytes ELSE 0 END),0) as uploadedBytes " +
        "FROM file_records GROUP BY category"
    )
    fun categoryTotalsFlow(): Flow<List<CategoryTotals>>

    @Query("SELECT uri FROM file_records")
    suspend fun allUris(): List<String>

    @Query("SELECT fingerprint FROM file_records WHERE status = 'UPLOADED'")
    suspend fun uploadedFingerprints(): List<String>

    @Query("SELECT COUNT(*) FROM file_records WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM file_records WHERE status = 'PENDING'")
    suspend fun pendingBytes(): Long

    @Query("UPDATE file_records SET status = :status, lastError = :error WHERE id = :id")
    suspend fun markStatus(id: Long, status: UploadStatus, error: String? = null)

    @Query(
        "UPDATE file_records SET status = 'UPLOADED', telegramMessageId = :messageId, " +
        "uploadedAtMillis = :uploadedAt, lastError = NULL WHERE id = :id"
    )
    suspend fun markUploaded(id: Long, messageId: Long, uploadedAt: Long)

    @Query(
        "UPDATE file_records SET status = 'FAILED', retryCount = retryCount + 1, lastError = :error WHERE id = :id"
    )
    suspend fun markFailed(id: Long, error: String)

    @Query("UPDATE file_records SET status = 'PENDING', lastError = NULL WHERE status = 'FAILED'")
    suspend fun retryAllFailed()

    @Query("UPDATE file_records SET status = 'PENDING', lastError = NULL WHERE id = :id")
    suspend fun retryOne(id: Long)

    /**
     * A row left in UPLOADING (process killed mid-upload, or the old code path that never
     * completed) was invisible to every query and would sit there forever. Called at the start
     * of each run so those files get another go.
     */
    @Query("UPDATE file_records SET status = 'PENDING' WHERE status = 'UPLOADING'")
    suspend fun resetInFlight(): Int

    /** Keeps queued rows pointing at the channel the user just typed in. */
    @Query(
        "UPDATE file_records SET destinationChannelId = :channelId " +
        "WHERE category = :category AND status != 'UPLOADED'"
    )
    suspend fun repointCategory(category: BackupCategory, channelId: Long)

    /**
     * One-time cleanup when whole-device scanning takes over: the same file would otherwise be
     * queued twice, once as a content:// SAF document and once as a file:// path. Rows that
     * already uploaded are kept so their fingerprints still suppress duplicates.
     */
    @Query("DELETE FROM file_records WHERE status != 'UPLOADED' AND uri LIKE 'content://%'")
    suspend fun deleteUnsentSafRows(): Int
}
