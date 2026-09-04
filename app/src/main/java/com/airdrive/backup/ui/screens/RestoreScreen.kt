package com.airdrive.backup.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.repo.BackupRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * The other half of a backup: pulling files back out of Telegram onto the phone. Only files this
 * install uploaded are listed, because restoring needs the (chat, message) pair recorded at upload
 * time. Restored files land in Downloads/AirDrive and never overwrite anything.
 *
 * Category filtering and multi-select restore (select individual items or everything currently
 * shown, then restore all of them in one pass) were the two things missing here — everything
 * before could only restore one file at a time with no way to narrow the list by type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(nav: NavHostController) {
    val context = LocalContext.current
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<BackupCategory?>(null) }
    val selected = remember { mutableStateListOf<Long>() }
    var bulkRunning by remember { mutableStateOf(false) }
    var bulkDone by remember { mutableStateOf(0) }
    var bulkTotal by remember { mutableStateOf(0) }
    var bulkFailed by remember { mutableStateOf(0) }

    val restorable by remember(query, categoryFilter) {
        repository.restorableFlow(query.trim(), categoryFilter?.name ?: "")
    }.collectAsState(initial = emptyList())
    val restore by repository.restoreState.collectAsState()

    // The list is filtered by search/category live, so a selection made under one filter can
    // point at rows no longer visible under another — drop anything that's fallen out of view
    // rather than silently restoring files the user can no longer see or meant to deselect.
    LaunchedEffect(restorable) {
        val visible = restorable.map { it.id }.toSet()
        selected.retainAll(visible)
    }

    val allSelected = restorable.isNotEmpty() && selected.size == restorable.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (selected.isEmpty()) "Restore from Telegram" else "${selected.size} selected")
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (selected.isEmpty()) nav.popBackStack() else selected.clear() }
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (restorable.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                if (allSelected) selected.clear() else {
                                    selected.clear()
                                    selected.addAll(restorable.map { it.id })
                                }
                            }
                        ) { Text(if (allSelected) "Clear" else "Select all") }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search uploaded files") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = categoryFilter == null,
                    onClick = { categoryFilter = null },
                    label = { Text("All") }
                )
                for (category in BackupCategory.values()) {
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = categoryFilter == category,
                        onClick = { categoryFilter = if (categoryFilter == category) null else category },
                        label = { Text(categoryLabel(category)) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            if (bulkRunning) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Restoring $bulkDone of $bulkTotal" +
                                if (bulkFailed > 0) " • $bulkFailed failed" else "",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { if (bulkTotal == 0) 0f else bulkDone.toFloat() / bulkTotal.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        restore?.let { state ->
                            if (state.running) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Now: ${state.fileName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            } else {
                restore?.let { state ->
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(state.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Spacer(Modifier.height(6.dp))
                            when {
                                state.error != null -> Text(
                                    state.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                state.finishedPath != null -> Text(
                                    "Saved to ${state.finishedPath}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                else -> {
                                    LinearProgressIndicator(
                                        progress = { state.fraction },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "${formatBytes(state.doneBytes)} of ${formatBytes(state.totalBytes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (!state.running) {
                                TextButton(onClick = { repository.clearRestoreState() }) { Text("Dismiss") }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            if (restorable.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank() && categoryFilter == null) {
                            "Nothing has been uploaded from this phone yet."
                        } else {
                            "No uploaded file matches that."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                items(restorable, key = { it.id }) { record ->
                    RestoreRow(
                        record = record,
                        checked = record.id in selected,
                        busy = restore?.running == true || bulkRunning,
                        onToggle = {
                            if (record.id in selected) selected.remove(record.id) else selected.add(record.id)
                        },
                        onRestore = {
                            scope.launch { runCatching { repository.restoreFile(record) } }
                        }
                    )
                    Divider()
                }
            }

            if (selected.isNotEmpty()) {
                Button(
                    onClick = {
                        val targets = restorable.filter { it.id in selected }
                        bulkRunning = true
                        bulkDone = 0
                        bulkFailed = 0
                        bulkTotal = targets.size
                        scope.launch {
                            for (record in targets) {
                                runCatching { repository.restoreFile(record) }
                                    .onFailure { bulkFailed++ }
                                bulkDone++
                            }
                            bulkRunning = false
                            selected.clear()
                        }
                    },
                    enabled = !bulkRunning && restore?.running != true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)
                ) { Text("Restore ${selected.size} selected") }
            }
        }
    }
}

@Composable
private fun RestoreRow(
    record: FileRecord,
    checked: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onRestore: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = !busy)
        Column(modifier = Modifier.weight(1f)) {
            Text(record.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                "${formatBytes(record.sizeBytes)} • " +
                    (record.uploadedAtMillis?.let { fmt.format(Date(it)) } ?: "uploaded"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onRestore, enabled = !busy) { Text("Restore") }
    }
}
