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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * The other half of a backup: pulling a file back out of Telegram onto the phone. Only files this
 * install uploaded are listed, because restoring needs the (chat, message) pair recorded at upload
 * time. Restored files land in Downloads/AirDrive and never overwrite anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(nav: NavHostController) {
    val context = LocalContext.current
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    val restorable by remember(query) { repository.restorableFlow(query.trim()) }
        .collectAsState(initial = emptyList())
    val restore by repository.restoreState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Restore from Telegram") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                Spacer(Modifier.height(8.dp))
            }

            if (restorable.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank()) "Nothing has been uploaded from this phone yet."
                        else "No uploaded file matches “$query”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(restorable, key = { it.id }) { record ->
                    RestoreRow(
                        record = record,
                        busy = restore?.running == true,
                        onRestore = {
                            scope.launch { runCatching { repository.restoreFile(record) } }
                        }
                    )
                    Divider()
                }
            }
        }
    }
}

@Composable
private fun RestoreRow(record: FileRecord, busy: Boolean, onRestore: () -> Unit) {
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
