package com.airdrive.backup.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class CategoryCount(val category: BackupCategory, val count: Int, val bytes: Long)

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
}
