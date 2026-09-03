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
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.data.repo.BackupRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Every file AirDrive knows about, searchable and filterable. Filtering happens in SQL rather
 * than in the list, because a full scan of a phone easily produces tens of thousands of rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityHistoryScreen(nav: NavHostController) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<UploadStatus?>(null) }

    val activity by remember(query, filter) {
        val trimmed = query.trim()
        val status = filter
        if (status == null) dao.activityFlow(trimmed, LIMIT)
        else dao.activityByStatusFlow(status, trimmed, LIMIT)
    }.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity") },
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
                label = { Text("Search by file name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = filter == null,
                    onClick = { filter = null },
                    label = { Text("All") }
                )
                for (status in UploadStatus.values()) {
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = filter == status,
                        onClick = { filter = if (filter == status) null else status },
                        label = { Text(statusLabel(status)) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            if (activity.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing matches that.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(activity, key = { it.id }) { record: FileRecord ->
                    ActivityRow(record, repository, scope)
                    Divider()
                }
                if (activity.size >= LIMIT) {
                    item {
                        Text(
                            "Showing the newest $LIMIT — search to narrow it down.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

private const val LIMIT = 300

@Composable
private fun ActivityRow(
    record: FileRecord,
    repository: BackupRepository,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(record.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                "${formatBytes(record.sizeBytes)} • " +
                    fmt.format(Date(record.uploadedAtMillis ?: record.addedAtMillis)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            record.lastError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2
                )
            }
        }
        // Cancel is only offered for a file that is still queued or actively uploading — an
        // already-UPLOADED or FAILED row has nothing left to interrupt.
        when (record.status) {
            UploadStatus.PENDING, UploadStatus.UPLOADING -> {
                TextButton(onClick = { scope.launch { repository.cancelUpload(record.id) } }) {
                    Text("Cancel")
                }
            }
            UploadStatus.CANCELLED -> {
                TextButton(onClick = { scope.launch { repository.requeueCancelled(record.id) } }) {
                    Text("Requeue")
                }
            }
            else -> Unit
        }
        StatusChip(record.status)
    }
}

private fun statusLabel(status: UploadStatus): String = when (status) {
    UploadStatus.UPLOADED -> "Uploaded"
    UploadStatus.FAILED -> "Failed"
    UploadStatus.UPLOADING -> "Uploading"
    UploadStatus.PENDING -> "Pending"
    UploadStatus.SKIPPED -> "Skipped"
    UploadStatus.CANCELLED -> "Cancelled"
}

@Composable
private fun StatusChip(status: UploadStatus) {
    val color = when (status) {
        UploadStatus.UPLOADED -> MaterialTheme.colorScheme.primary
        UploadStatus.FAILED -> MaterialTheme.colorScheme.error
        UploadStatus.UPLOADING -> MaterialTheme.colorScheme.tertiary
        UploadStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
        UploadStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        UploadStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    AssistChip(onClick = {}, label = { Text(statusLabel(status), color = color) })
}
