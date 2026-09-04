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

/**
 * The narrow projection the incremental scanner loads up front — one row per tracked file,
 * just enough to decide unchanged / modified / renamed / missing without pulling display
 * names, captions and error strings for 20 000 files into memory.
 */
data class KnownFile(
    val id: Long,
    val uri: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val status: UploadStatus,
    val fingerprint: String,
    val localState: LocalState
)

/** Row count per [VerifyState], for the verification summary card. */
data class VerifyCount(val verifyState: VerifyState, val count: Int)

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
        "AND (:categoryName = '' OR category = :categoryName) " +
        "ORDER BY addedAtMillis DESC LIMIT :limit"
    )
    fun activityFlow(query: String, categoryName: String, limit: Int): Flow<List<FileRecord>>

    @Query(
        "SELECT * FROM file_records " +
        "WHERE status = :status AND (:query = '' OR displayName LIKE '%' || :query || '%') " +
        "AND (:categoryName = '' OR category = :categoryName) " +
        "ORDER BY addedAtMillis DESC LIMIT :limit"
    )
    fun activityByStatusFlow(status: UploadStatus, query: String, categoryName: String, limit: Int): Flow<List<FileRecord>>

    /** Files that can be pulled back out of Telegram: uploaded, and with a message to fetch. */
    @Query(
        "SELECT * FROM file_records " +
        "WHERE status = 'UPLOADED' AND telegramMessageId IS NOT NULL " +
        "AND (:query = '' OR displayName LIKE '%' || :query || '%') " +
        "AND (:categoryName = '' OR category = :categoryName) " +
        "ORDER BY uploadedAtMillis DESC LIMIT :limit"
    )
    fun restorableFlow(query: String, categoryName: String, limit: Int): Flow<List<FileRecord>>

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
     *
     * [runId] ties the file to the backup run that moved it, which is what lets the timeline show
     * "these 127 files" for a given entry. Null when a file is uploaded outside a tracked run.
     * A successful upload also clears any stale verification verdict — the bytes in Telegram are
     * new, so the old VERIFIED/MISSING_REMOTE answer no longer applies.
     */
    @Query(
        "UPDATE file_records SET status = 'UPLOADED', telegramMessageId = :messageId, " +
        "destinationChannelId = :chatId, uploadedAtMillis = :uploadedAt, lastError = NULL, " +
        "lastRunId = :runId, verifyState = 'UNVERIFIED', verifiedAtMillis = NULL " +
        "WHERE id = :id"
    )
    suspend fun markUploaded(id: Long, messageId: Long, chatId: Long, uploadedAt: Long, runId: Long? = null)

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

    // ------------------------------------------------------------------ incremental scan

    /**
     * Snapshot of every tracked file, keyed by uri at the call site. Loaded once per scan: the
     * scanner then decides per file whether the bytes moved, instead of asking the database
     * 20 000 times.
     */
    @Query(
        "SELECT id, uri, sizeBytes, modifiedAtMillis, status, fingerprint, localState " +
        "FROM file_records"
    )
    suspend fun knownFiles(): List<KnownFile>

    /** Fingerprints of uploaded files with the row id, so a rename can be repointed. */
    @Query("SELECT id, uri, sizeBytes, modifiedAtMillis, status, fingerprint, localState " +
        "FROM file_records WHERE status = 'UPLOADED'")
    suspend fun uploadedKnownFiles(): List<KnownFile>

    /**
     * The file changed on disk: new size/timestamp/fingerprint and back into the queue as the
     * next revision. [telegramMessageId] and [uploadedAtMillis] are deliberately left alone —
     * until the new bytes actually land, the previous copy in Telegram is still the best one
     * available, and throwing away its coordinates would orphan it.
     */
    @Query(
        "UPDATE file_records SET sizeBytes = :size, modifiedAtMillis = :modified, " +
        "fingerprint = :fingerprint, status = 'PENDING', localState = 'PRESENT', " +
        "localStateAtMillis = NULL, verifyState = 'UNVERIFIED', verifiedAtMillis = NULL, " +
        "revision = revision + 1, retryCount = 0, lastError = NULL WHERE id = :id"
    )
    suspend fun requeueModified(id: Long, size: Long, modified: Long, fingerprint: String)

    /**
     * The timestamp moved but the bytes hashed the same — a copy, a touch, a media-scanner
     * rewrite. Only the snapshot needs updating; touching [status] here would re-upload a file
     * whose contents never changed, which is the exact bug incremental backup exists to avoid.
     */
    @Query(
        "UPDATE file_records SET sizeBytes = :size, modifiedAtMillis = :modified, " +
        "localState = 'PRESENT', localStateAtMillis = NULL WHERE id = :id"
    )
    suspend fun touchSnapshot(id: Long, size: Long, modified: Long)

    /**
     * Same bytes at a new path — a rename or a move. Repointing keeps the Telegram copy and
     * skips the upload entirely, which is the whole point of fingerprinting.
     *
     * OR IGNORE because `uri` is unique: if some other row already claims the new path the
     * update is dropped rather than crashing the scan.
     */
    @Query(
        "UPDATE OR IGNORE file_records SET uri = :uri, displayName = :name, " +
        "modifiedAtMillis = :modified, localState = 'PRESENT', localStateAtMillis = NULL " +
        "WHERE id = :id"
    )
    suspend fun repointToNewLocation(id: Long, uri: String, name: String, modified: Long): Int

    /**
     * The presence sweep. Both take chunked id lists — SQLite allows at most 999 bound
     * variables per statement, so callers must chunk (see FileScanner.CHUNK_IDS).
     *
     * A file only becomes MISSING if it was PRESENT: rows restored from the manifest (UNKNOWN)
     * and copies AirDrive itself freed (FREED) must not be relabelled by a scan.
     */
    @Query(
        "UPDATE file_records SET localState = 'MISSING', localStateAtMillis = :now " +
        "WHERE id IN (:ids) AND localState = 'PRESENT'"
    )
    suspend fun markMissingBatch(ids: List<Long>, now: Long): Int

    /** A file that had vanished and is back — or a manifest row whose real file was just found. */
    @Query(
        "UPDATE file_records SET localState = 'PRESENT', localStateAtMillis = NULL " +
        "WHERE id IN (:ids) AND localState != 'PRESENT'"
    )
    suspend fun markPresentBatch(ids: List<Long>): Int

    @Query("SELECT COUNT(*) FROM file_records WHERE localState = 'MISSING'")
    suspend fun missingCount(): Int

    // ------------------------------------------------------------------ runs / timeline

    /** What a given run actually uploaded, newest first — the "see exactly what changed" list. */
    @Query(
        "SELECT * FROM file_records WHERE lastRunId = :runId " +
        "ORDER BY uploadedAtMillis DESC LIMIT :limit"
    )
    fun filesForRunFlow(runId: Long, limit: Int): Flow<List<FileRecord>>

    @Query(
        "SELECT category, COUNT(*) as count, COALESCE(SUM(sizeBytes),0) as bytes " +
        "FROM file_records WHERE lastRunId = :runId GROUP BY category"
    )
    fun runBreakdownFlow(runId: Long): Flow<List<CategoryCount>>

    @Query("SELECT COUNT(*) FROM file_records WHERE lastRunId = :runId")
    suspend fun countForRun(runId: Long): Int

    // ------------------------------------------------------------------ deleted-file protection

    /**
     * Files that are gone from the phone but still safe in Telegram. FREED rows (deleted by the
     * cleanup assistant) are included so the user has one place to see everything that is
     * cloud-only.
     */
    @Query(
        "SELECT * FROM file_records " +
        "WHERE localState IN ('MISSING','FREED') AND status = 'UPLOADED' " +
        "AND (:query = '' OR displayName LIKE '%' || :query || '%') " +
        "ORDER BY localStateAtMillis DESC, id DESC LIMIT :limit"
    )
    fun cloudOnlyFlow(query: String, limit: Int): Flow<List<FileRecord>>

    @Query("SELECT COUNT(*) FROM file_records WHERE localState = 'MISSING' AND status = 'UPLOADED'")
    fun missingCountFlow(): Flow<Int>

    @Query(
        "SELECT COALESCE(SUM(sizeBytes),0) FROM file_records " +
        "WHERE localState IN ('MISSING','FREED') AND status = 'UPLOADED'"
    )
    fun cloudOnlyBytesFlow(): Flow<Long>

    /** "Keep forever" pins a file against the auto-purge sweep. */
    @Query("UPDATE file_records SET keepForever = :keep WHERE id = :id")
    suspend fun setKeepForever(id: Long, keep: Boolean)

    @Query("UPDATE file_records SET localState = :state, localStateAtMillis = :at WHERE id = :id")
    suspend fun setLocalState(id: Long, state: LocalState, at: Long?)

    /**
     * Rows eligible for the optional "auto-delete after X days" sweep: missing locally for
     * longer than the grace period and not pinned. Never selects FREED rows — the user asked
     * AirDrive to delete those locally precisely because the Telegram copy is the keeper.
     */
    @Query(
        "SELECT * FROM file_records WHERE localState = 'MISSING' AND status = 'UPLOADED' " +
        "AND keepForever = 0 AND telegramMessageId IS NOT NULL " +
        "AND localStateAtMillis IS NOT NULL AND localStateAtMillis <= :cutoff " +
        "ORDER BY localStateAtMillis ASC LIMIT :limit"
    )
    suspend fun autoPurgeCandidates(cutoff: Long, limit: Int): List<FileRecord>

    /** Used once the Telegram copy is confirmed deleted; the row has nothing left to point at. */
    @Query("DELETE FROM file_records WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    // ------------------------------------------------------------------ global search

    /**
     * The one query behind the search screen. Every filter is optional and disabled by a
     * sentinel ('' for text and enum names, 0 for ids and bounds) so the whole thing stays a
     * single prepared statement instead of string-built SQL — filtering 20 000 rows in Kotlin
     * would mean loading 20 000 rows.
     *
     * Enum filters are passed as names rather than enum values because Room cannot express
     * "no filter" for an enum parameter; the columns are TEXT, so comparing names is exact.
     *
     * [sort]: 0 newest, 1 largest, 2 name A–Z, 3 name Z–A, 4 oldest, 5 smallest.
     */
    @Query(
        "SELECT * FROM file_records WHERE " +
        "(:query = '' OR displayName LIKE '%' || :query || '%' OR uri LIKE '%' || :query || '%') " +
        "AND (:categoryName = '' OR category = :categoryName) " +
        "AND (:statusName = '' OR status = :statusName) " +
        "AND (:localStateName = '' OR localState = :localStateName) " +
        "AND (:folder = '' OR uri LIKE '%' || :folder || '%') " +
        "AND (:chatId = 0 OR destinationChannelId = :chatId) " +
        "AND sizeBytes >= :minBytes " +
        "AND (:maxBytes <= 0 OR sizeBytes <= :maxBytes) " +
        "AND modifiedAtMillis >= :fromMillis " +
        "AND (:toMillis <= 0 OR modifiedAtMillis <= :toMillis) " +
        "ORDER BY " +
        "CASE WHEN :sort = 1 THEN sizeBytes END DESC, " +
        "CASE WHEN :sort = 2 THEN displayName END ASC, " +
        "CASE WHEN :sort = 3 THEN displayName END DESC, " +
        "CASE WHEN :sort = 4 THEN modifiedAtMillis END ASC, " +
        "CASE WHEN :sort = 5 THEN sizeBytes END ASC, " +
        "modifiedAtMillis DESC, id DESC " +
        "LIMIT :limit"
    )
    fun searchFlow(
        query: String,
        categoryName: String,
        statusName: String,
        localStateName: String,
        folder: String,
        chatId: Long,
        minBytes: Long,
        maxBytes: Long,
        fromMillis: Long,
        toMillis: Long,
        sort: Int,
        limit: Int
    ): Flow<List<FileRecord>>

    /** Same predicate as [searchFlow], count only, so the UI can say "showing 200 of 3 412". */
    @Query(
        "SELECT COUNT(*) FROM file_records WHERE " +
        "(:query = '' OR displayName LIKE '%' || :query || '%' OR uri LIKE '%' || :query || '%') " +
        "AND (:categoryName = '' OR category = :categoryName) " +
        "AND (:statusName = '' OR status = :statusName) " +
        "AND (:localStateName = '' OR localState = :localStateName) " +
        "AND (:folder = '' OR uri LIKE '%' || :folder || '%') " +
        "AND (:chatId = 0 OR destinationChannelId = :chatId) " +
        "AND sizeBytes >= :minBytes " +
        "AND (:maxBytes <= 0 OR sizeBytes <= :maxBytes) " +
        "AND modifiedAtMillis >= :fromMillis " +
        "AND (:toMillis <= 0 OR modifiedAtMillis <= :toMillis)"
    )
    fun searchCountFlow(
        query: String,
        categoryName: String,
        statusName: String,
        localStateName: String,
        folder: String,
        chatId: Long,
        minBytes: Long,
        maxBytes: Long,
        fromMillis: Long,
        toMillis: Long
    ): Flow<Int>

    /** Populates the "Telegram destination" filter with chats that actually hold something. */
    @Query(
        "SELECT DISTINCT destinationChannelId FROM file_records " +
        "WHERE destinationChannelId != 0 ORDER BY destinationChannelId"
    )
    fun destinationChatIdsFlow(): Flow<List<Long>>

    // ------------------------------------------------------------------ gallery / media

    /**
     * Newest-first media feed for the gallery. Month headers are cut in Kotlin from
     * [FileRecord.modifiedAtMillis] rather than in SQL, because SQLite's strftime works in UTC
     * and would put a late-evening photo in the wrong month for most of the world.
     *
     * Categories arrive as names: Room has no clean way to bind a list of enums.
     */
    @Query(
        "SELECT * FROM file_records WHERE category IN (:categoryNames) " +
        "AND (:query = '' OR displayName LIKE '%' || :query || '%') " +
        "AND (:onlyBackedUp = 0 OR status = 'UPLOADED') " +
        "ORDER BY modifiedAtMillis DESC, id DESC LIMIT :limit"
    )
    fun galleryFlow(
        categoryNames: List<String>,
        query: String,
        onlyBackedUp: Boolean,
        limit: Int
    ): Flow<List<FileRecord>>

    @Query(
        "SELECT COUNT(*) FROM file_records WHERE category IN (:categoryNames) " +
        "AND (:onlyBackedUp = 0 OR status = 'UPLOADED')"
    )
    fun galleryCountFlow(categoryNames: List<String>, onlyBackedUp: Boolean): Flow<Int>

    /** Cached once per file by the media browser; extracting it costs a MediaMetadataRetriever. */
    @Query("UPDATE file_records SET durationMillis = :duration WHERE id = :id")
    suspend fun setDuration(id: Long, duration: Long)

    // ------------------------------------------------------------------ device-to-device restore

    /**
     * The bulk-restore queue for a new phone. Ordered by id so a resumed migration continues
     * roughly where it stopped, and [restoredAtMillis] is what makes it resumable at all.
     */
    @Query(
        "SELECT * FROM file_records WHERE status = 'UPLOADED' AND telegramMessageId IS NOT NULL " +
        "AND (:categoryName = '' OR category = :categoryName) " +
        "AND (:skipRestored = 0 OR restoredAtMillis IS NULL) " +
        "ORDER BY id ASC LIMIT :limit"
    )
    suspend fun restoreQueue(categoryName: String, skipRestored: Boolean, limit: Int): List<FileRecord>

    @Query(
        "SELECT COUNT(*) FROM file_records WHERE status = 'UPLOADED' " +
        "AND telegramMessageId IS NOT NULL " +
        "AND (:categoryName = '' OR category = :categoryName) " +
        "AND (:skipRestored = 0 OR restoredAtMillis IS NULL)"
    )
    suspend fun restoreQueueCount(categoryName: String, skipRestored: Boolean): Int

    /** Per-category "what can I pull down" totals for the migration picker. */
    @Query(
        "SELECT category, COUNT(*) as count, COALESCE(SUM(sizeBytes),0) as bytes " +
        "FROM file_records WHERE status = 'UPLOADED' AND telegramMessageId IS NOT NULL " +
        "AND restoredAtMillis IS NULL GROUP BY category"
    )
    fun restorableTotalsFlow(): Flow<List<CategoryCount>>

    @Query("UPDATE file_records SET restoredAtMillis = :now WHERE id = :id")
    suspend fun markRestored(id: Long, now: Long)

    @Query("SELECT COUNT(*) FROM file_records WHERE restoredAtMillis IS NOT NULL")
    fun restoredCountFlow(): Flow<Int>

    /** Lets the user run a migration again from scratch. */
    @Query(
        "UPDATE file_records SET restoredAtMillis = NULL " +
        "WHERE (:categoryName = '' OR category = :categoryName)"
    )
    suspend fun clearRestoreMarks(categoryName: String): Int

    // ------------------------------------------------------------------ storage cleanup

    /**
     * Local copies that are safe to delete. The predicate is the safety guarantee, so read it
     * as a contract: uploaded, with a real Telegram message to fetch it back from, still
     * present on disk, and a plain file path AirDrive can actually delete (a content:// SAF
     * document may not be deletable at all).
     *
     * [verifiedOnly] additionally demands that the Telegram copy was re-checked after upload.
     * Largest first, because the point is to free space.
     */
    @Query(
        "SELECT * FROM file_records WHERE status = 'UPLOADED' AND telegramMessageId IS NOT NULL " +
        "AND localState = 'PRESENT' AND uri LIKE 'file://%' " +
        "AND (:categoryName = '' OR category = :categoryName) " +
        "AND (:verifiedOnly = 0 OR verifyState = 'VERIFIED') " +
        "ORDER BY sizeBytes DESC, id DESC LIMIT :limit"
    )
    suspend fun cleanupCandidates(
        categoryName: String,
        verifiedOnly: Boolean,
        limit: Int
    ): List<FileRecord>

    @Query(
        "SELECT * FROM file_records WHERE status = 'UPLOADED' AND telegramMessageId IS NOT NULL " +
        "AND localState = 'PRESENT' AND uri LIKE 'file://%' " +
        "AND (:categoryName = '' OR category = :categoryName) " +
        "AND (:verifiedOnly = 0 OR verifyState = 'VERIFIED') " +
        "ORDER BY sizeBytes DESC, id DESC LIMIT :limit"
    )
    fun cleanupCandidatesFlow(
        categoryName: String,
        verifiedOnly: Boolean,
        limit: Int
    ): Flow<List<FileRecord>>

    /** The "you can safely free" figures on the cleanup screen, per category. */
    @Query(
        "SELECT category, COUNT(*) as count, COALESCE(SUM(sizeBytes),0) as bytes " +
        "FROM file_records WHERE status = 'UPLOADED' AND telegramMessageId IS NOT NULL " +
        "AND localState = 'PRESENT' AND uri LIKE 'file://%' GROUP BY category"
    )
    fun cleanupTotalsFlow(): Flow<List<CategoryCount>>

    @Query("SELECT COALESCE(SUM(sizeBytes),0) FROM file_records WHERE localState = 'FREED'")
    fun freedBytesFlow(): Flow<Long>

    /** Local copy deleted by AirDrive after the Telegram copy was confirmed. */
    @Query(
        "UPDATE file_records SET localState = 'FREED', localStateAtMillis = :now WHERE id = :id"
    )
    suspend fun markFreed(id: Long, now: Long)

    // ------------------------------------------------------------------ verification

    /**
     * Files due for a Telegram-side check. Never-verified rows come first, then the ones
     * verified longest ago, so a nightly pass with a small budget still rotates through the
     * whole library.
     */
    @Query(
        "SELECT * FROM file_records WHERE status = 'UPLOADED' AND telegramMessageId IS NOT NULL " +
        "AND (:onlyUnchecked = 0 OR verifyState = 'UNVERIFIED') " +
        "ORDER BY CASE WHEN verifiedAtMillis IS NULL THEN 0 ELSE 1 END ASC, " +
        "verifiedAtMillis ASC, id ASC LIMIT :limit"
    )
    suspend fun verifyQueue(onlyUnchecked: Boolean, limit: Int): List<FileRecord>

    @Query("UPDATE file_records SET verifyState = :state, verifiedAtMillis = :now WHERE id = :id")
    suspend fun setVerifyState(id: Long, state: VerifyState, now: Long)

    @Query(
        "SELECT verifyState, COUNT(*) as count FROM file_records " +
        "WHERE status = 'UPLOADED' GROUP BY verifyState"
    )
    fun verifyBreakdownFlow(): Flow<List<VerifyCount>>

    @Query(
        "SELECT * FROM file_records WHERE verifyState IN ('MISSING_REMOTE','SIZE_MISMATCH') " +
        "ORDER BY verifiedAtMillis DESC, id DESC LIMIT :limit"
    )
    fun verifyProblemsFlow(limit: Int): Flow<List<FileRecord>>

    @Query("SELECT COUNT(*) FROM file_records WHERE verifyState IN ('MISSING_REMOTE','SIZE_MISMATCH')")
    fun verifyProblemCountFlow(): Flow<Int>

    /**
     * Verification found nothing (or the wrong thing) at the recorded message, so the file goes
     * back in the queue. Only meaningful while the local copy still exists — a FREED file has
     * nothing left to re-upload, which is exactly why cleanup insists on verifying first.
     */
    @Query(
        "UPDATE file_records SET status = 'PENDING', telegramMessageId = NULL, " +
        "uploadedAtMillis = NULL, retryCount = 0, lastError = NULL, " +
        "verifyState = 'UNVERIFIED', verifiedAtMillis = NULL " +
        "WHERE id = :id AND localState = 'PRESENT'"
    )
    suspend fun requeueForRepair(id: Long): Int
}
