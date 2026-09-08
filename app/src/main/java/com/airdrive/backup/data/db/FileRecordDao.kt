package com.airdrive.backup.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class CategoryCount(val category: BackupCategory, val count: Int, val bytes: Long)
data class CategoryTotals(val category: BackupCategory, val total: Int, val totalBytes: Long, val uploaded: Int, val uploadedBytes: Long)
data class KnownFile(val id: Long, val uri: String, val sizeBytes: Long, val modifiedAtMillis: Long, val status: UploadStatus, val fingerprint: String, val localState: LocalState)
data class VerifyCount(val verifyState: VerifyState, val count: Int)

@Dao
interface FileRecordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(record: FileRecord): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(records: List<FileRecord>): List<Long>
    @Update suspend fun update(record: FileRecord)
    @Query("SELECT * FROM file_records WHERE fingerprint = :fingerprint LIMIT 1") suspend fun findByFingerprint(fingerprint: String): FileRecord?
    @Query("SELECT * FROM file_records WHERE uri = :uri LIMIT 1") suspend fun findByUri(uri: String): FileRecord?
    @Query("SELECT * FROM file_records WHERE status = 'PENDING' ORDER BY addedAtMillis ASC") suspend fun pendingFiles(): List<FileRecord>
    @Query("SELECT * FROM file_records WHERE status = 'PENDING' ORDER BY addedAtMillis ASC LIMIT :limit") suspend fun nextPendingBatch(limit: Int): List<FileRecord>
    @Query("SELECT * FROM file_records WHERE status = 'PENDING' ORDER BY modifiedAtMillis DESC LIMIT :limit") suspend fun nextPendingNewest(limit: Int): List<FileRecord>
    @Query("SELECT * FROM file_records WHERE status = 'PENDING' ORDER BY sizeBytes ASC LIMIT :limit") suspend fun nextPendingSmallest(limit: Int): List<FileRecord>
    @Query("SELECT * FROM file_records WHERE status = 'PENDING' AND category = :category ORDER BY addedAtMillis ASC LIMIT :limit") suspend fun nextPendingBatchForCategory(category: BackupCategory, limit: Int): List<FileRecord>
    @Query("SELECT * FROM file_records WHERE status = 'PENDING' AND category = :category ORDER BY modifiedAtMillis DESC LIMIT :limit") suspend fun nextPendingNewestForCategory(category: BackupCategory, limit: Int): List<FileRecord>
    @Query("SELECT * FROM file_records WHERE status = 'PENDING' AND category = :category ORDER BY sizeBytes ASC LIMIT :limit") suspend fun nextPendingSmallestForCategory(category: BackupCategory, limit: Int): List<FileRecord>
    @Query("SELECT COUNT(*) FROM file_records WHERE status = 'PENDING' AND category = :category") suspend fun pendingCountForCategory(category: BackupCategory): Int
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM file_records WHERE status = 'PENDING' AND category = :category") suspend fun pendingBytesForCategory(category: BackupCategory): Long
    @Query("SELECT * FROM file_records WHERE status = 'FAILED' ORDER BY uploadedAtMillis DESC") fun failedFilesFlow(): Flow<List<FileRecord>>
    @Query("SELECT * FROM file_records WHERE status = 'UPLOADED' ORDER BY uploadedAtMillis DESC LIMIT :limit") fun recentUploadsFlow(limit: Int): Flow<List<FileRecord>>
    @Query("SELECT * FROM file_records ORDER BY addedAtMillis DESC LIMIT :limit") fun recentActivityFlow(limit: Int): Flow<List<FileRecord>>
    @Query("SELECT * FROM file_records WHERE (:query = '' OR displayName LIKE '%' || :query || '%') AND (:categoryName = '' OR category = :categoryName) ORDER BY addedAtMillis DESC LIMIT :limit") fun activityFlow(query: String, categoryName: String, limit: Int): Flow<List<FileRecord>>
    @Query("SELECT * FROM file_records WHERE status = :status AND (:query = '' OR displayName LIKE '%' || :query || '%') AND (:categoryName = '' OR category = :categoryName) ORDER BY addedAtMillis DESC LIMIT :limit") fun activityByStatusFlow(status: UploadStatus, query: String, categoryName: String, limit: Int): Flow<List<FileRecord>>
    @Query("SELECT * FROM file_records WHERE status = 'UPLOADED' AND telegramMessageId IS NOT NULL AND (:query = '' OR displayName LIKE '%' || :query || '%') AND (:categoryName = '' OR category = :categoryName) ORDER BY uploadedAtMillis DESC LIMIT :limit") fun restorableFlow(query: String, categoryName: String, limit: Int): Flow<List<FileRecord>>
    @Query("SELECT * FROM file_records WHERE status = 'UPLOADED' ORDER BY uploadedAtMillis ASC LIMIT :limit OFFSET :offset") suspend fun uploadedPage(limit: Int, offset: Int): List<FileRecord>
    @Query("SELECT COUNT(*) FROM file_records WHERE status = 'UPLOADED'") fun uploadedCountFlow(): Flow<Int>
    @Query("SELECT COUNT(*) FROM file_records WHERE status = 'PENDING'") fun pendingCountFlow(): Flow<Int>
    @Query("SELECT COUNT(*) FROM file_records WHERE status = 'FAILED'") fun failedCountFlow(): Flow<Int>
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM file_records WHERE status = 'UPLOADED'") fun uploadedBytesFlow(): Flow<Long>
    @Query("SELECT MAX(uploadedAtMillis) FROM file_records WHERE status = 'UPLOADED'") fun lastBackupTimeFlow(): Flow<Long?>
    @Query("SELECT category, COUNT(*) as count, COALESCE(SUM(sizeBytes),0) as bytes FROM file_records WHERE status = 'UPLOADED' GROUP BY category") fun categoryBreakdownFlow(): Flow<List<CategoryCount>>
    @Query("SELECT category, COUNT(*) as total, COALESCE(SUM(sizeBytes),0) as totalBytes, COALESCE(SUM(CASE WHEN status = 'UPLOADED' THEN 1 ELSE 0 END),0) as uploaded, COALESCE(SUM(CASE WHEN status = 'UPLOADED' THEN sizeBytes ELSE 0 END),0) as uploadedBytes FROM file_records GROUP BY category") fun categoryTotalsFlow(): Flow<List<CategoryTotals>>
    @Query("SELECT uri FROM file_records") suspend fun allUris(): List<String>
    @Query("SELECT fingerprint FROM file_records WHERE status = 'UPLOADED'") suspend fun uploadedFingerprints(): List<String>
    @Query("SELECT COUNT(*) FROM file_records WHERE status = 'PENDING'") suspend fun pendingCount(): Int
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM file_records WHERE status = 'PENDING'") suspend fun pendingBytes(): Long
    @Query("SELECT COUNT(*) FROM file_records WHERE status = 'FAILED'") suspend fun failedCount(): Int
    @Query("SELECT COUNT(*) FROM file_records WHERE status = 'UPLOADED'") suspend fun uploadedCount(): Int
    @Query("SELECT COUNT(*) FROM file_records") suspend fun totalRowCount(): Int
    @Query("UPDATE file_records SET status = :status, lastError = :error WHERE id = :id") suspend fun markStatus(id: Long, status: UploadStatus, error: String? = null)
    @Query("UPDATE file_records SET status = 'UPLOADED', telegramMessageId = :messageId, destinationChannelId = :chatId, uploadedAtMillis = :uploadedAt, lastError = NULL, lastRunId = :runId, verifyState = 'UNVERIFIED', verifiedAtMillis = NULL WHERE id = :id") suspend fun markUploaded(id: Long, messageId: Long, chatId: Long, uploadedAt: Long, runId: Long? = null)
    @Query("UPDATE file_records SET status = 'FAILED', retryCount = retryCount + 1, lastError = :error WHERE id = :id") suspend fun markFailed(id: Long, error: String)
    @Query("UPDATE file_records SET status = 'PENDING', lastError = NULL WHERE status = 'FAILED'") suspend fun retryAllFailed()
    @Query("UPDATE file_records SET status = 'PENDING', lastError = NULL WHERE status = 'FAILED' AND retryCount < :maxRetries") suspend fun retryFailedUnder(maxRetries: Int): Int
    @Query("UPDATE file_records SET status = 'PENDING', lastError = NULL WHERE id = :id") suspend fun retryOne(id: Long)
    @Query("UPDATE file_records SET status = 'PENDING' WHERE status = 'UPLOADING'") suspend fun resetInFlight(): Int
    @Query("UPDATE file_records SET destinationChannelId = :channelId WHERE category = :category AND status != 'UPLOADED'") suspend fun repointCategory(category: BackupCategory, channelId: Long)
    @Query("DELETE FROM file_records WHERE status != 'UPLOADED' AND uri LIKE 'content://%'") suspend fun deleteUnsentSafRows(): Int
    @Query("UPDATE file_records SET status = 'CANCELLED', lastError = NULL WHERE id = :id") suspend fun markCancelled(id: Long)
    @Query("SELECT * FROM file_records WHERE status = 'CANCELLED' ORDER BY addedAtMillis DESC") fun cancelledFilesFlow(): Flow<List<FileRecord>>
    @Query("UPDATE file_records SET status = 'PENDING', lastError = NULL WHERE id = :id") suspend fun requeueCancelled(id: Long)
    @Query("UPDATE file_records SET status = 'CANCELLED' WHERE status = 'PENDING'") suspend fun cancelAllPending(): Int
    @Query("SELECT * FROM file_records WHERE status = 'UPLOADED' ORDER BY id ASC LIMIT :limit OFFSET :offset") suspend fun uploadedPageById(limit: Int, offset: Int): List<FileRecord>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRestored(records: List<FileRecord>): List<Long>

    /** Existing remote message lookup prevents a full channel rescan from creating duplicates. */
    @Query("SELECT * FROM file_records WHERE destinationChannelId = :chatId AND telegramMessageId = :messageId LIMIT 1")
    suspend fun findByTelegramMessage(chatId: Long, messageId: Long): FileRecord?

    @Query("SELECT id, uri, sizeBytes, modifiedAtMillis, status, fingerprint, localState FROM file_records") suspend fun knownFiles(): List<KnownFile>
    @Query("SELECT id, uri, sizeBytes, modifiedAtMillis, status, fingerprint, localState FROM file_records WHERE status = 'UPLOADED'") suspend fun uploadedKnownFiles(): List<KnownFile>
    @Query("UPDATE file_records SET sizeBytes = :size, modifiedAtMillis = :modified, fingerprint = :fingerprint, status = 'PENDING', localState = 'PRESENT', localStateAtMillis = NULL, verifyState = 'UNVERIFIED', verifiedAtMillis = NULL, revision = revision + 1, retryCount = 0, lastError = NULL WHERE id = :id") suspend fun requeueModified(id: Long, size: Long, modified: Long, fingerprint: String)
    @Query("UPDATE file_records SET sizeBytes = :size, modifiedAtMillis = :modified, localState = 'PRESENT', localStateAtMillis = NULL WHERE id = :id") suspend fun touchSnapshot(id: Long, size: Long, modified: Long)
    @Query("UPDATE OR IGNORE file_records SET uri = :uri, displayName = :name, modifiedAtMillis = :modified, localState = 'PRESENT', localStateAtMillis = NULL WHERE id = :id") suspend fun repointToNewLocation(id: Long, uri: String, name: String, modified: Long): Int
    @Query("UPDATE file_records SET localState = 'MISSING', localStateAtMillis = :now WHERE id IN (:ids) AND localState = 'PRESENT'") suspend fun markMissingBatch(ids: List<Long>, now: Long): Int
    @Query("UPDATE file_records SET localState = 'PRESENT', localStateAtMillis = NULL WHERE id IN (:ids) AND localState != 'PRESENT'") suspend fun markPresentBatch(ids: List<Long>): Int
    @Query("SELECT COUNT(*) FROM file_records WHERE localState = 'MISSING'") suspend fun missingCount(): Int
    @Query("SELECT * FROM file_records WHERE lastRunId = :runId ORDER BY uploadedAtMillis DESC LIMIT :limit") fun filesForRunFlow(runId: Long, limit: Int): Flow<List<FileRecord>>
    @Query("SELECT category, COUNT(*) as count, COALESCE(SUM(sizeBytes),0) as bytes FROM file_records WHERE lastRunId = :runId GROUP BY category") fun runBreakdownFlow(runId: Long): Flow<List<CategoryCount>>
    @Query("SELECT COUNT(*) FROM file_records WHERE lastRunId = :runId") suspend fun countForRun(runId: Long): Int

    @Query("SELECT * FROM file_records WHERE localState IN ('MISSING','FREED') AND status = 'UPLOADED' ORDER BY localStateAtMillis DESC") fun deletedFilesFlow(): Flow<List<FileRecord>>
    @Query("SELECT * FROM file_records WHERE status = 'UPLOADED' AND category = :category ORDER BY uploadedAtMillis DESC LIMIT :limit") fun galleryFlow(category: BackupCategory, limit: Int): Flow<List<FileRecord>>
    @Query("SELECT COUNT(*) FROM file_records WHERE status = 'UPLOADED' AND category = :category") fun galleryCountFlow(category: BackupCategory): Flow<Int>
    @Query("SELECT * FROM file_records WHERE status = 'UPLOADED' AND (:query = '' OR displayName LIKE '%' || :query || '%') ORDER BY uploadedAtMillis DESC LIMIT :limit") fun searchFlow(query: String, limit: Int): Flow<List<FileRecord>>
    @Query("SELECT * FROM file_records WHERE status = 'UPLOADED' AND category = :category ORDER BY uploadedAtMillis DESC") fun allForCategoryFlow(category: BackupCategory): Flow<List<FileRecord>>
}
