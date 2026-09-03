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

    /** Newest photos first, which is what people actually want to see land in Telegram first. */
    @Query("SELECT * FROM file_records WHERE status = 'PENDING' ORDER BY modifiedAtMillis DESC LIMIT :limit")
    suspend fun nextPendingNewest(limit: Int): List<FileRecord>

    /** Clears the file count fastest on a slow connection. */
    @Query("SELECT * FROM file_records WHERE status = 'PENDING' ORDER BY sizeBytes ASC LIMIT :limit")
    suspend fun nextPendingSmallest(limit: Int): List<FileRecord>

    @Query(
        "SELECT * FROM file_records WHERE status = 'PENDING' AND category = :category " +
        "ORDER BY addedAtMillis ASC LIMIT :limit"
    )
    suspend fun nextPendingBatchForCategory(category: BackupCategory, limit: Int): List<FileRecord>

    @Query(
        "SELECT * FROM file_records WHERE status = 'PENDING' AND category = :category " +
        "ORDER BY modifiedAtMillis DESC LIMIT :limit"
    )
    suspend fun nextPendingNewestForCategory(category: BackupCategory, limit: Int): List<FileRecord>

    @Query(
        "SELECT * FROM file_records WHERE status = 'PENDING' AND category = :category " +
        "ORDER BY sizeBytes ASC LIMIT :limit"
    )
    suspend fun nextPendingSmallestForCategory(category: BackupCategory, limit: Int): List<FileRecord>

    @Query("SELECT COUNT(*) FROM file_records WHERE status = 'PENDING' AND category = :category")
    suspend fun pendingCountForCategory(category: BackupCategory): Int

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM file_records WHERE status = 'PENDING' AND category = :category")
    suspend fun pendingBytesForCategory(category: BackupCategory): Long

    @Query("SELECT * FROM file_records WHERE status = 'FAILED' ORDER BY uploadedAtMillis DESC")
    fun failedFilesFlow(): Flow<List<FileRecord>>

    @Query("SELECT * FROM file_records WHERE status = 'UPLOADED' ORDER BY uploadedAtMillis DESC LIMIT :limit")
    fun recentUploadsFlow(limit: Int): Flow<List<FileRecord>>

    @Query("SELECT * FROM file_records ORDER BY addedAtMillis DESC LIMIT :limit")
    fun recentActivityFlow(limit: Int): Flow<List<FileRecord>>

    /**
     * Activity with an optional name search. An empty [query] matches everything, so one query
     * serves both the plain list and the search box — filtering 13k rows in the UI is not an
     * option.
     */
    @Query(
        "SELECT * FROM file_records " +
        "WHERE (:query = '' OR displayName LIKE '%' || :query || '%') " +
        "ORDER BY addedAtMillis DESC LIMIT :limit"
    )
    fun activityFlow(query: String, limit: Int): Flow<List<FileRecord>>

    @Query(
        "SELECT * FROM file_records " +
        "WHERE status = :status AND (:query = '' OR displayName LIKE '%' || :query || '%') " +
        "ORDER BY addedAtMillis DESC LIMIT :limit"
    )
    fun activityByStatusFlow(status: UploadStatus, query: String, limit: Int): Flow<List<FileRecord>>

    /** Files that can be pulled back out of Telegram: uploaded, and with a message to fetch. */
    @Query(
        "SELECT * FROM file_records " +
        "WHERE status = 'UPLOADED' AND telegramMessageId IS NOT NULL " +
        "AND (:query = '' OR displayName LIKE '%' || :query || '%') " +
        "ORDER BY uploadedAtMillis DESC LIMIT :limit"
    )
    fun restorableFlow(query: String, limit: Int): Flow<List<FileRecord>>

    /** Paged so the CSV export never holds the whole table in memory. */
    @Query("SELECT * FROM file_records WHERE status = 'UPLOADED' ORDER BY uploadedAtMillis ASC LIMIT :limit OFFSET :offset")
    suspend fun uploadedPage(limit: Int, offset: Int): List<FileRecord>

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

    @Query("SELECT COUNT(*) FROM file_records WHERE status = 'FAILED'")
    suspend fun failedCount(): Int

    @Query("SELECT COUNT(*) FROM file_records WHERE status = 'UPLOADED'")
    suspend fun uploadedCount(): Int

    @Query("UPDATE file_records SET status = :status, lastError = :error WHERE id = :id")
    suspend fun markStatus(id: Long, status: UploadStatus, error: String? = null)

    /**
     * [chatId] is the chat the bytes actually landed in, which is not necessarily the category's
     * configured channel any more (Saved Messages and single-chat mode both exist). Restore needs
     * the pair (chat, message) to find the file again, so it is recorded at the moment of success.
     */
    @Query(
        "UPDATE file_records SET status = 'UPLOADED', telegramMessageId = :messageId, " +
        "destinationChannelId = :chatId, uploadedAtMillis = :uploadedAt, lastError = NULL " +
        "WHERE id = :id"
    )
    suspend fun markUploaded(id: Long, messageId: Long, chatId: Long, uploadedAt: Long)

    @Query(
        "UPDATE file_records SET status = 'FAILED', retryCount = retryCount + 1, lastError = :error WHERE id = :id"
    )
    suspend fun markFailed(id: Long, error: String)

    @Query("UPDATE file_records SET status = 'PENDING', lastError = NULL WHERE status = 'FAILED'")
    suspend fun retryAllFailed()

    /**
     * Bounded version used automatically at the start of each run: a file that has already failed
     * [maxRetries] times is left alone so a genuinely unreadable file cannot churn forever.
     */
    @Query(
        "UPDATE file_records SET status = 'PENDING', lastError = NULL " +
        "WHERE status = 'FAILED' AND retryCount < :maxRetries"
    )
    suspend fun retryFailedUnder(maxRetries: Int): Int

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

    // ------------------------------------------------------------------ cancel / requeue

    /** Marks one file as cancelled by the user. It simply drops out of every PENDING query. */
    @Query("UPDATE file_records SET status = 'CANCELLED', lastError = NULL WHERE id = :id")
    suspend fun markCancelled(id: Long)

    @Query("SELECT * FROM file_records WHERE status = 'CANCELLED' ORDER BY addedAtMillis DESC")
    fun cancelledFilesFlow(): Flow<List<FileRecord>>

    /** Puts a cancelled file back in the queue if the user changes their mind. */
    @Query("UPDATE file_records SET status = 'PENDING', lastError = NULL WHERE id = :id")
    suspend fun requeueCancelled(id: Long)

    @Query("UPDATE file_records SET status = 'CANCELLED' WHERE status = 'PENDING'")
    suspend fun cancelAllPending(): Int

    // ------------------------------------------------------------------ manifest restore

    /**
     * Everything needed to rebuild the Telegram backup-data manifest: only UPLOADED rows,
     * fetched a page at a time so a 10k-file library never sits in memory all at once.
     */
    @Query(
        "SELECT * FROM file_records WHERE status = 'UPLOADED' " +
        "ORDER BY id ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun uploadedPageById(limit: Int, offset: Int): List<FileRecord>

    /**
     * A row restored from the Telegram manifest on a fresh install. [uri] is a synthetic
     * "restored://<fingerprint>" placeholder — the original SAF/file uri is meaningless after
     * a reinstall — so it satisfies the unique index without colliding with a real file the
     * scanner finds later. The scanner's own fingerprint check is what actually prevents the
     * real file from being queued again once it is rescanned.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRestored(records: List<FileRecord>): List<Long>

    @Query("SELECT COUNT(*) FROM file_records")
    suspend fun totalRowCount(): Int
}
