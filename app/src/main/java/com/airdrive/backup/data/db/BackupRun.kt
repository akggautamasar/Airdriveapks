package com.airdrive.backup.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** What started a run, so the timeline can say "you tapped this" vs "it happened by itself". */
enum class RunTrigger { MANUAL, AUTOMATIC, CATEGORY, RESTORE, VERIFY, CLEANUP }

/**
 * How a run ended. RUNNING is the live row; a row left RUNNING after the process dies is
 * repaired on the next start (see [BackupRunDao.closeStaleRuns]).
 */
enum class RunOutcome { RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED, BLOCKED }

/**
 * One backup / restore / verify pass, which is what the Backup timeline screen is built from.
 * Counters are written as the run progresses rather than at the end, so a run that is killed
 * mid-way still shows what it managed to do.
 *
 * [categoryFilter] holds a [BackupCategory] name, or "" for a full run — a plain String on
 * purpose so the column needs no nullable-enum converter.
 */
@Entity(
    tableName = "backup_runs",
    indices = [Index(value = ["startedAtMillis"]), Index(value = ["outcome"])]
)
data class BackupRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val finishedAtMillis: Long? = null,
    /** Named startedBy rather than "trigger", which is a reserved word in SQLite. */
    val startedBy: RunTrigger = RunTrigger.MANUAL,
    val categoryFilter: String = "",
    val outcome: RunOutcome = RunOutcome.RUNNING,

    /** Everything the walk looked at, including files that were already backed up. */
    val filesScanned: Int = 0,
    val filesNew: Int = 0,
    val filesModified: Int = 0,
    val filesMissing: Int = 0,
    val filesRenamed: Int = 0,

    val filesUploaded: Int = 0,
    val filesFailed: Int = 0,
    val bytesUploaded: Long = 0,

    /** Short human-readable outcome, e.g. "Sign in to Telegram to continue". */
    val note: String? = null
) {
    val durationMillis: Long?
        get() = finishedAtMillis?.let { (it - startedAtMillis).coerceAtLeast(0L) }

    /** True when this run actually moved bytes, as opposed to finding nothing to do. */
    val didSomething: Boolean
        get() = filesUploaded > 0 || filesFailed > 0
}

/** The [BackupCategory] a run was restricted to, or null for a full run. */
fun BackupRun.filterCategory(): BackupCategory? =
    categoryFilter.takeIf { it.isNotEmpty() }
        ?.let { runCatching { BackupCategory.valueOf(it) }.getOrNull() }
