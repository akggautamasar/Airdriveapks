package com.airdrive.backup.ui.screens

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.*
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.ui.theme.AirSuccess
import com.airdrive.backup.util.Format
import com.airdrive.backup.util.MediaThumbnails
import com.airdrive.backup.util.Sharing
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private const val LIMIT = 600

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferenceCategoryDetailScreen(nav: NavHostController, category: BackupCategory) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()
    var statusFilter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<FileRecord?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val categoryNames = remember(category) { listOf(category.name) }
    val files by remember(category, statusFilter) {
        if (statusFilter == "PENDING") {
            dao.activityByStatusFlow(UploadStatus.PENDING, "", category.name, LIMIT)
        } else {
            dao.galleryFlow(categoryNames, "", statusFilter == "UPLOADED", LIMIT)
        }
    }.collectAsState(initial = emptyList())
    val total by remember(category, statusFilter) {
        if (statusFilter == "PENDING") {
            kotlinx.coroutines.flow.flowOf(files.size)
        } else {
            dao.galleryCountFlow(categoryNames, statusFilter == "UPLOADED")
        }
    }.collectAsState(initial = 0)
    val restore by repository.restoreState.collectAsState()
    val isMedia = category == BackupCategory.PHOTOS || category == BackupCategory.VIDEOS
    val entries = remember(files, isMedia) { if (isMedia) referenceGroupByMonth(files) else emptyList() }

    DisposableEffect(Unit) { onDispose { MediaThumbnails.trim() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(categoryLabel(category)) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReferenceChip("All", statusFilter.isEmpty()) { statusFilter = "" }
                ReferenceChip("Pending", statusFilter == "PENDING") { statusFilter = "PENDING" }
                ReferenceChip("Uploaded", statusFilter == "UPLOADED") { statusFilter = "UPLOADED" }
            }
            Text(
                if (files.size >= LIMIT) "Newest ${Format.count(files.size)} of ${Format.count(total)}" else "${Format.count(files.size)} of ${Format.count(total)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp)
            )
            restore?.let { state ->
                if (state.running || state.error != null || state.finishedPath != null) {
                    Column(Modifier.padding(horizontal = 28.dp, vertical = 4.dp)) {
                        Text(
                            when {
                                state.error != null -> "Restore failed: ${state.error}"
                                state.finishedPath != null -> "Saved to ${state.finishedPath}"
                                else -> "Restoring ${state.fileName}…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.running) LinearProgressIndicator(progress = { state.fraction }, Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 28.dp)) }

            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No files found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else if (isMedia) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(entries, key = { if (it is RefEntry.Header) "h:${it.label}" else "f:${(it as RefEntry.Media).record.id}" }, span = { if (it is RefEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { entry ->
                        when (entry) {
                            is RefEntry.Header -> Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 3.dp)) {
                                Text(entry.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                                Text("${Format.count(entry.count)} items", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            is RefEntry.Media -> ReferenceMediaCell(entry.record) { selected = entry.record; message = null }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 28.dp)) {
                    items(files, key = { it.id }) { record ->
                        ReferenceFileRow(record) { selected = record; message = null }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    selected?.let { record ->
        val local = record.localBytesAvailable()
        AlertDialog(
            onDismissRequest = { if (!busy) selected = null },
            title = { Text(record.displayName, maxLines = 2) },
            text = { Text("${categoryLabel(record.category)} • ${Format.bytes(record.sizeBytes)}") },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
            confirmButton = {
                Row {
                    if (local) TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            val staged = Sharing.stage(context, Uri.parse(record.uri), record.displayName)
                            if (staged == null) message = "Could not open this file."
                            else if (!Sharing.open(context, staged, record.displayName)) message = "No app can open this file."
                            busy = false; selected = null
                        }
                    }) { Text("Open") }
                    if (record.status == UploadStatus.UPLOADED && record.telegramMessageId != null) TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            runCatching { repository.restoreFile(record) }.onFailure { message = it.message ?: "Restore failed" }
                            busy = false; selected = null
                        }
                    }) { Text("Restore") }
                }
            }
        )
    }
}

@Composable
private fun ReferenceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, shape = RoundedCornerShape(11.dp), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onSurface, containerColor = MaterialTheme.colorScheme.background, labelColor = MaterialTheme.colorScheme.onSurfaceVariant))
}

private sealed interface RefEntry {
    data class Header(val label: String, val count: Int) : RefEntry
    data class Media(val record: FileRecord) : RefEntry
}

private fun referenceGroupByMonth(files: List<FileRecord>): List<RefEntry> {
    val fmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    val cal = Calendar.getInstance()
    val buckets = LinkedHashMap<String, MutableList<FileRecord>>()
    files.forEach { r -> cal.timeInMillis = r.modifiedAtMillis; val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"; buckets.getOrPut(key) { mutableListOf() }.add(r) }
    return buildList { buckets.values.forEach { rows -> add(RefEntry.Header(fmt.format(Date(rows.first().modifiedAtMillis)), rows.size)); rows.forEach { add(RefEntry.Media(it)) } } }
}

@Composable
private fun ReferenceMediaCell(record: FileRecord, onClick: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(record.uri) { mutableStateOf(MediaThumbnails.peek(record)) }
    LaunchedEffect(record.uri) { if (bitmap == null) bitmap = MediaThumbnails.load(context, record) }
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
        bitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = record.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Text(extensionLabel(record.displayName), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
        Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(9.dp).clip(CircleShape).background(if (record.status == UploadStatus.UPLOADED) AirSuccess else MaterialTheme.colorScheme.error))
        if (record.category == BackupCategory.VIDEOS) Surface(color = Color.Black.copy(alpha = .35f), shape = CircleShape, modifier = Modifier.align(Alignment.Center)) { Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.padding(4.dp).size(23.dp)) }
    }
}

@Composable
private fun ReferenceFileRow(record: FileRecord, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text(extensionLabel(record.displayName), style = MaterialTheme.typography.labelSmall) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(record.displayName, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
            Text("${Format.bytes(record.sizeBytes)} • ${record.status.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun FileRecord.localBytesAvailable(): Boolean = localState != LocalState.MISSING && localState != LocalState.FREED && (uri.startsWith("file://") || uri.startsWith("content://"))
