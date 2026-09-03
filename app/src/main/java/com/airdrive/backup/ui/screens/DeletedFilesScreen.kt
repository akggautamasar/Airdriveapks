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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.LocalState
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.util.Format
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wishlist item 4, "Deleted File Protection": a file leaving the phone does not mean the backup
 * should be forgotten. Everything the last scan could not find is listed here with its Telegram
 * copy intact, and the user decides — restore it, pin it, or remove it for good.
 *
 * The destructive action is deliberately awkward: it needs a confirmation dialog, and the copy is
 * only deleted after Telegram has confirmed it is really there. Nothing here happens on its own
 * unless the auto-delete switch below the list has been turned on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeletedFilesScreen(nav: NavHostController) {
    val context = LocalContext.current
    val repository = remember { BackupRepository.get(context) }
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    val files by remember(query) { repository.cloudOnlyFlow(query.trim(), LIMIT) }
        .collectAsState(initial = emptyList())
    val protectedBytes by remember { repository.cloudOnlyBytesFlow() }.collectAsState(initial = 0L)
    val autoDelete by settings.autoDeleteMissingEnabled.collectAsState(initial = false)
    val autoDeleteDays by settings.autoDeleteMissingDays
        .collectAsState(initial = SettingsStore.DEFAULT_AUTO_DELETE_DAYS)

    val restore by repository.restoreState.collectAsState()
    var pendingPurge by remember { mutableStateOf<FileRecord?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Deleted from this phone") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "${Format.bytes(protectedBytes)} still safe in Telegram",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "These files are no longer on the phone. AirDrive keeps their Telegram " +
                            "copies until you say otherwise.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search by file name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )

            restore?.let { state ->
                Spacer(Modifier.height(8.dp))
                Column(Modifier.padding(horizontal = 16.dp)) {
                    when {
                        state.error != null -> Text(
                            "Restore failed: ${state.error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        state.finishedPath != null -> Text(
                            "Saved to ${state.finishedPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        else -> {
                            Text(
                                "Restoring ${state.fileName}…",
                                style = MaterialTheme.typography.bodySmall
                            )
                            LinearProgressIndicator(
                                progress = { state.fraction },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                if (files.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (query.isBlank()) {
                                    "Nothing has gone missing. Every backed-up file is still on the phone."
                                } else {
                                    "No deleted files match that."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(files, key = { it.id }) { record: FileRecord ->
                    DeletedFileRow(
                        record = record,
                        busy = busy,
                        onRestore = {
                            busy = true
                            message = null
                            scope.launch {
                                runCatching { repository.restoreFile(record) }
                                    .onSuccess { repository.markRestoredLocally(record) }
                                    .onFailure { message = it.message?.take(200) }
                                busy = false
                            }
                        },
                        onKeepForever = {
                            scope.launch { repository.setKeepForever(record.id, !record.keepForever) }
                        },
                        onPurge = { pendingPurge = record }
                    )
                    Divider()
                }

                if (files.size >= LIMIT) {
                    item {
                        Text(
                            "Showing the $LIMIT most recent — search to narrow it down.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }

            AutoDeleteSection(
                enabled = autoDelete,
                days = autoDeleteDays,
                onToggle = { on -> scope.launch { settings.setAutoDeleteMissingEnabled(on) } },
                onDays = { d -> scope.launch { settings.setAutoDeleteMissingDays(d) } }
            )
        }
    }

    pendingPurge?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingPurge = null },
            title = { Text("Delete the Telegram copy?") },
            text = {
                Text(
                    "${record.displayName} is already gone from this phone. Removing the Telegram " +
                        "copy deletes the last copy that exists anywhere — this cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingPurge = null
                        busy = true
                        message = null
                        scope.launch {
                            val result = repository.purgeRemoteCopy(record)
                            message = result.fold(
                                onSuccess = { "Deleted ${record.displayName} from Telegram." },
                                onFailure = { "Could not delete it: ${it.message?.take(160)}" }
                            )
                            busy = false
                        }
                    }
                ) { Text("Delete for good", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPurge = null }) { Text("Keep it") }
            }
        )
    }
}

private const val LIMIT = 300

@Composable
private fun DeletedFileRow(
    record: FileRecord,
    busy: Boolean,
    onRestore: () -> Unit,
    onKeepForever: () -> Unit,
    onPurge: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(record.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    "${categoryLabel(record.category)} • ${Format.bytes(record.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    localStateLine(record, fmt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (record.keepForever) {
                AssistChip(
                    onClick = onKeepForever,
                    label = { Text("Kept", color = MaterialTheme.colorScheme.primary) }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRestore, enabled = !busy) { Text("Restore") }
            TextButton(onClick = onKeepForever) {
                Text(if (record.keepForever) "Unpin" else "Keep forever")
            }
            TextButton(onClick = onPurge, enabled = !busy) {
                Text("Delete copy", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** "Gone from this phone since 3 September 2026", or the cleanup assistant's own wording. */
private fun localStateLine(record: FileRecord, fmt: SimpleDateFormat): String {
    val stamp = record.localStateAtMillis?.let { fmt.format(Date(it)) }
    return when (record.localState) {
        LocalState.FREED ->
            if (stamp != null) "Local copy freed by AirDrive on $stamp" else "Local copy freed by AirDrive"
        LocalState.MISSING ->
            if (stamp != null) "Gone from this phone since $stamp" else "Gone from this phone"
        else -> "Cloud copy only"
    }
}

@Composable
private fun AutoDeleteSection(
    enabled: Boolean,
    days: Long,
    onToggle: (Boolean) -> Unit,
    onDays: (Long) -> Unit
) {
    Divider()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-delete after a while", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (enabled) {
                        "Telegram copies of files missing for more than $days days are removed " +
                            "after the next backup. Pinned files are never touched."
                    } else {
                        "Off. Copies are kept until you delete them yourself — the safe default."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        if (enabled) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (option in DAY_OPTIONS) {
                    FilterChip(
                        selected = days == option,
                        onClick = { onDays(option) },
                        label = { Text("$option d") },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        }
    }
}

private val DAY_OPTIONS = listOf(7L, 30L, 90L, 365L)
