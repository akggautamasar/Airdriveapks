package com.airdrive.backup.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.BackupRun
import com.airdrive.backup.data.db.RunOutcome
import com.airdrive.backup.data.db.RunTrigger
import com.airdrive.backup.data.db.filterCategory
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.Format
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The backup history as a timeline: day headers, then each run with what it actually did. Tapping
 * a run opens the list of files that run touched.
 *
 * Day grouping is done here rather than in SQL because SQLite's strftime works in UTC, which would
 * file a late-evening backup under the wrong day for most of the world.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupTimelineScreen(nav: NavHostController) {
    val context = LocalContext.current
    val repository = remember { BackupRepository.get(context) }
    val runs by remember { repository.recentRunsFlow(LIMIT) }.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup timeline") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (runs.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No backups yet. Once you run one, it shows up here with exactly what changed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        val grouped = remember(runs) { runs.groupBy { dayKey(it.startedAtMillis) } }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            for ((day, dayRuns) in grouped) {
                item(key = "header_$day") {
                    Text(
                        dayLabel(dayRuns.first().startedAtMillis),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                }
                items(dayRuns, key = { it.id }) { run ->
                    RunRow(run) { nav.navigate("${Routes.RUN_DETAIL}/${run.id}") }
                    Divider()
                }
            }
            if (runs.size >= LIMIT) {
                item {
                    Text(
                        "Showing the most recent $LIMIT runs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }
    }
}

private const val LIMIT = 100

@Composable
private fun RunRow(run: BackupRun, onClick: () -> Unit) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            timeFmt.format(Date(run.startedAtMillis)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                outcomeTitle(run),
                style = MaterialTheme.typography.bodyLarge,
                color = outcomeColor(run.outcome)
            )
            Text(
                runDetailLine(run),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val extra = runChangeLine(run)
            if (extra != null) {
                Text(
                    extra,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            run.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2
                )
            }
        }
    }
}

/** "Backup completed", plus what limited it — a category run is not the same as a full one. */
private fun outcomeTitle(run: BackupRun): String {
    val base = when (run.outcome) {
        RunOutcome.RUNNING -> "Backup in progress"
        RunOutcome.COMPLETED -> if (run.didSomething) "Backup completed" else "Nothing to back up"
        RunOutcome.PARTIAL -> "Backup incomplete"
        RunOutcome.FAILED -> "Backup failed"
        RunOutcome.CANCELLED -> "Backup interrupted"
        RunOutcome.BLOCKED -> "Backup couldn't start"
    }
    val category = run.filterCategory()?.let { categoryLabel(it) }
    val suffix = when {
        category != null -> " • $category only"
        run.startedBy == RunTrigger.AUTOMATIC -> " • automatic"
        else -> ""
    }
    return base + suffix
}

private fun runDetailLine(run: BackupRun): String {
    val parts = buildList {
        if (run.filesUploaded > 0) {
            add("${Format.count(run.filesUploaded)} file(s) • ${Format.bytes(run.bytesUploaded)}")
        }
        if (run.filesFailed > 0) add("${Format.count(run.filesFailed)} failed")
        if (run.filesUploaded == 0 && run.filesFailed == 0 && run.filesScanned > 0) {
            add("${Format.count(run.filesScanned)} file(s) checked")
        }
        run.durationMillis?.let { if (it >= 1000L) add("took ${Format.elapsed(it)}") }
    }
    return if (parts.isEmpty()) "—" else parts.joinToString(" • ")
}

/** The incremental story for this run, only shown when there is something to say. */
private fun runChangeLine(run: BackupRun): String? {
    val parts = buildList {
        if (run.filesNew > 0) add("${Format.count(run.filesNew)} new")
        if (run.filesModified > 0) add("${Format.count(run.filesModified)} changed")
        if (run.filesRenamed > 0) add("${Format.count(run.filesRenamed)} moved")
        if (run.filesMissing > 0) add("${Format.count(run.filesMissing)} deleted locally")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

@Composable
private fun outcomeColor(outcome: RunOutcome): Color = when (outcome) {
    RunOutcome.COMPLETED -> MaterialTheme.colorScheme.primary
    RunOutcome.PARTIAL -> MaterialTheme.colorScheme.tertiary
    RunOutcome.FAILED, RunOutcome.BLOCKED -> MaterialTheme.colorScheme.error
    RunOutcome.RUNNING -> MaterialTheme.colorScheme.tertiary
    RunOutcome.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Local-calendar day key, so runs group the way the user's own day does. */
private fun dayKey(millis: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = millis
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

fun dayLabel(millis: Long): String {
    val today = dayKey(System.currentTimeMillis())
    val day = dayKey(millis)
    val dayMillis = 24L * 60L * 60L * 1000L
    return when {
        day == today -> "Today"
        day == today - dayMillis -> "Yesterday"
        today - day < 6L * dayMillis -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(millis))
        else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(millis))
    }
}
