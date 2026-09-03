package com.airdrive.backup.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FileRecord::class, BackupRun::class, FileVersion::class],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fileRecordDao(): FileRecordDao
    abstract fun backupRunDao(): BackupRunDao
    abstract fun fileVersionDao(): FileVersionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * v1 → v2 adds the incremental-scan / verification / local-presence columns and the
         * backup_runs table. Written out by hand rather than falling back destructively: these
         * rows are the only record of which files already live in Telegram, and losing them
         * would make the next scan re-upload the user's entire phone.
         *
         * Every added column is nullable or carries a DEFAULT, which is what SQLite requires for
         * ALTER TABLE ADD COLUMN on a table that already has rows.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `file_records` ADD COLUMN `localState` TEXT NOT NULL DEFAULT 'PRESENT'")
                db.execSQL("ALTER TABLE `file_records` ADD COLUMN `localStateAtMillis` INTEGER")
                db.execSQL("ALTER TABLE `file_records` ADD COLUMN `keepForever` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `file_records` ADD COLUMN `verifyState` TEXT NOT NULL DEFAULT 'UNVERIFIED'")
                db.execSQL("ALTER TABLE `file_records` ADD COLUMN `verifiedAtMillis` INTEGER")
                db.execSQL("ALTER TABLE `file_records` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `file_records` ADD COLUMN `lastRunId` INTEGER")
                db.execSQL("ALTER TABLE `file_records` ADD COLUMN `durationMillis` INTEGER")
                db.execSQL("ALTER TABLE `file_records` ADD COLUMN `restoredAtMillis` INTEGER")

                // Rows rebuilt from the Telegram manifest have no local path at all, so they must
                // not start life claiming the file is present on this phone.
                db.execSQL(
                    "UPDATE `file_records` SET `localState` = 'UNKNOWN' WHERE `uri` LIKE 'restored://%'"
                )

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_records_category` ON `file_records` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_file_records_localState` ON `file_records` (`localState`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `backup_runs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`startedAtMillis` INTEGER NOT NULL, " +
                        "`finishedAtMillis` INTEGER, " +
                        "`startedBy` TEXT NOT NULL, " +
                        "`categoryFilter` TEXT NOT NULL, " +
                        "`outcome` TEXT NOT NULL, " +
                        "`filesScanned` INTEGER NOT NULL, " +
                        "`filesNew` INTEGER NOT NULL, " +
                        "`filesModified` INTEGER NOT NULL, " +
                        "`filesMissing` INTEGER NOT NULL, " +
                        "`filesRenamed` INTEGER NOT NULL, " +
                        "`filesUploaded` INTEGER NOT NULL, " +
                        "`filesFailed` INTEGER NOT NULL, " +
                        "`bytesUploaded` INTEGER NOT NULL, " +
                        "`note` TEXT)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_backup_runs_startedAtMillis` " +
                        "ON `backup_runs` (`startedAtMillis`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_backup_runs_outcome` ON `backup_runs` (`outcome`)"
                )
            }
        }

        /**
         * v2 → v3 adds file_versions: one row per upload, so an older copy of a file that is still
         * sitting in Telegram stays reachable after the record's message id has been overwritten.
         *
         * The backfill matters more than the table. Every already-uploaded file gets its current
         * version recorded, which means history starts populated with what is actually known rather
         * than empty; the copies from before this migration were never written down anywhere, so
         * they are genuinely unrecoverable and the screen says as much.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `file_versions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`recordId` INTEGER NOT NULL, " +
                        "`revision` INTEGER NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`modifiedAtMillis` INTEGER NOT NULL, " +
                        "`fingerprint` TEXT NOT NULL, " +
                        "`chatId` INTEGER NOT NULL, " +
                        "`telegramMessageId` INTEGER, " +
                        "`uploadedAtMillis` INTEGER NOT NULL, " +
                        "`runId` INTEGER)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_file_versions_recordId_revision` " +
                        "ON `file_versions` (`recordId`, `revision`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_file_versions_uploadedAtMillis` " +
                        "ON `file_versions` (`uploadedAtMillis`)"
                )

                db.execSQL(
                    "INSERT OR IGNORE INTO `file_versions` (" +
                        "`recordId`, `revision`, `displayName`, `sizeBytes`, `modifiedAtMillis`, " +
                        "`fingerprint`, `chatId`, `telegramMessageId`, `uploadedAtMillis`, `runId`) " +
                        "SELECT `id`, `revision`, `displayName`, `sizeBytes`, `modifiedAtMillis`, " +
                        "`fingerprint`, `destinationChannelId`, `telegramMessageId`, " +
                        "COALESCE(`uploadedAtMillis`, `addedAtMillis`), `lastRunId` " +
                        "FROM `file_records` " +
                        "WHERE `status` = 'UPLOADED' AND `telegramMessageId` IS NOT NULL"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "airdrive.db"
                )
                    // Real migrations only. Never add fallbackToDestructiveMigration() here.
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
