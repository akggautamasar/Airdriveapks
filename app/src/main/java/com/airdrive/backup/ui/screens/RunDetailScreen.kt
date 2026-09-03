package com.airdrive.backup.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.util.Format
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Tap a backup → see exactly what changed." The files listed here are the ones this run uploaded,
 * which is what `lastRunId` on each record records.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunDetailScreen(nav: NavHostController, runId: Long) {
    val context = LocalContext.current
    val repository = remember { BackupRepository.get(context) }

    val run by remember(runId) { repository.runFlow(runId) }.collectAsState(initial = null)
    val files by remember(runId) { repository.filesForRunFlow(runId, LIMIT) }
        .collectAsState(initial = emptyList())
    val breakdown by remember(runId) { repository.runBreakdownFlow(runId) }
        .collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(run?.let { dayLabel(it.startedAtMillis) } ?: "Backup") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                val current = run
                if (current == null) {
                    Text(
                        "This backup is no longer in the timeline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val startFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Started ${startFmt.format(Date(current.startedAtMillis))}" +
                                    (current.durationMillis?.let { " • took ${Format.elapsed(it)}" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            StatLine("Uploaded", "${Format.count(current.filesUploaded)} file(s) • ${Format.bytes(current.bytesUploaded)}")
                            if (current.filesFailed > 0) StatLine("Failed", Format.count(current.filesFailed))
                            StatLine("Scanned", Format.count(current.filesScanned))
                            if (current.filesNew > 0) StatLine("New", Format.count(current.filesNew))
                            if (current.filesModified > 0) StatLine("Changed", Format.count(current.filesModified))
                            if (current.filesRenamed > 0) {
                                StatLine("Moved or renamed", Format.count(current.filesRenamed))
                            }
                            if (current.filesMissing > 0) {
                                StatLine("Deleted from phone", Format.count(current.filesMissing))
                            }
                            current.note?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            if (breakdown.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(20.dp))
                    Text("By category", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                }
                items(breakdown, key = { it.category.name }) { row ->
                    StatLine(
                        categoryLabel(row.category),
                        "${Format.count(row.count)} • ${Format.bytes(row.bytes)}"
                    )
                }
            }

            item {
                Spacer(Modifier.height(20.dp))
                Text(
                    if (files.isEmpty()) "No files were uploaded in this backup"
                    else "Files uploaded in this backup",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
            }

            items(files, key = { it.id }) { record: FileRecord ->
                FileLine(record)
                Divider()
            }

            if (files.size >= LIMIT) {
                item {
                    Text(
                        "Showing the first $LIMIT files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }
    }
}

private const val LIMIT = 500

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FileLine(record: FileRecord) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(record.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                "${categoryLabel(record.category)} • ${Format.bytes(record.sizeBytes)}" +
                    if (record.revision > 1) " • version ${record.revision}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
