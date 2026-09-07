package com.airdrive.backup.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.*
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.util.MediaThumbnails
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityHistoryScreen(nav: NavHostController) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<UploadStatus?>(null) }
    var categoryFilter by remember { mutableStateOf<BackupCategory?>(null) }
    val activity by remember(query, filter, categoryFilter) {
        val q = query.trim(); val category = categoryFilter?.name ?: ""
        if (filter == null) dao.activityFlow(q, category, LIMIT) else dao.activityByStatusFlow(filter!!, q, category, LIMIT)
    }.collectAsState(initial = emptyList())
    DisposableEffect(Unit) { onDispose { MediaThumbnails.trim() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Search by file name") }, singleLine = true, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 28.dp))
            Spacer(Modifier.height(12.dp))
            FilterRow {
                ReferenceFilterChip("All categories", categoryFilter == null) { categoryFilter = null }
                BackupCategory.values().forEach { category ->
                    Spacer(Modifier.width(10.dp)); ReferenceFilterChip(categoryLabel(category), categoryFilter == category) { categoryFilter = if (categoryFilter == category) null else category }
                }
            }
            Spacer(Modifier.height(8.dp))
            FilterRow {
                ReferenceFilterChip("All", filter == null) { filter = null }
                UploadStatus.values().forEach { status ->
                    Spacer(Modifier.width(10.dp)); ReferenceFilterChip(statusLabel(status), filter == status) { filter = if (filter == status) null else status }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (activity.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing matches that.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
                    items(activity, key = { it.id }) { record -> ActivityRow(record, repository, scope); HorizontalDivider() }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun ReferenceFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, shape = RoundedCornerShape(11.dp), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onSurface, containerColor = MaterialTheme.colorScheme.background, labelColor = MaterialTheme.colorScheme.onSurfaceVariant))
}

private const val LIMIT = 300

@Composable
private fun ActivityRow(record: FileRecord, repository: BackupRepository, scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val showsThumbnail = record.category == BackupCategory.PHOTOS || record.category == BackupCategory.VIDEOS
    var bitmap by remember(record.uri) { mutableStateOf(if (showsThumbnail) MediaThumbnails.peek(record) else null) }
    LaunchedEffect(record.uri, showsThumbnail) { if (showsThumbnail && bitmap == null) bitmap = MediaThumbnails.load(context, record) }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), record.displayName, ContentScale.Crop, Modifier.fillMaxSize()) }
                ?: Text(extensionLabel(record.displayName), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(record.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text("${formatBytes(record.sizeBytes)} • ${fmt.format(Date(record.uploadedAtMillis ?: record.addedAtMillis))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            record.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1) }
        }
        when (record.status) {
            UploadStatus.PENDING, UploadStatus.UPLOADING -> TextButton(onClick = { scope.launch { repository.cancelUpload(record.id) } }) { Text("Cancel") }
            UploadStatus.CANCELLED -> TextButton(onClick = { scope.launch { repository.requeueCancelled(record.id) } }) { Text("Requeue") }
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
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    AssistChip(onClick = {}, label = { Text(statusLabel(status), color = color) })
}
