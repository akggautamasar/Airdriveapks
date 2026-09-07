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
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.util.MediaThumbnails
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

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
    val restorable by remember(query, categoryFilter) { repository.restorableFlow(query.trim(), categoryFilter?.name ?: "") }.collectAsState(initial = emptyList())
    val restore by repository.restoreState.collectAsState()
    LaunchedEffect(restorable) { selected.retainAll(restorable.map { it.id }.toSet()) }
    val allSelected = restorable.isNotEmpty() && selected.size == restorable.size
    DisposableEffect(Unit) { onDispose { MediaThumbnails.trim() } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (selected.isEmpty()) "Restore from Telegram" else "${selected.size} selected") },
            navigationIcon = { IconButton(onClick = { if (selected.isEmpty()) nav.popBackStack() else selected.clear() }) { Icon(Icons.Default.ArrowBack, "Back") } },
            actions = { if (restorable.isNotEmpty()) TextButton(onClick = { if (allSelected) selected.clear() else { selected.clear(); selected.addAll(restorable.map { it.id }) } }) { Text(if (allSelected) "Clear" else "Select all") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Search uploaded files") }, singleLine = true, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 28.dp))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                ReferenceFilterChip("All", categoryFilter == null) { categoryFilter = null }
                BackupCategory.values().forEach { category -> Spacer(Modifier.width(10.dp)); ReferenceFilterChip(categoryLabel(category), categoryFilter == category) { categoryFilter = if (categoryFilter == category) null else category } }
            }
            Spacer(Modifier.height(10.dp))
            if (bulkRunning) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 28.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) { Text("Restoring $bulkDone of $bulkTotal" + if (bulkFailed > 0) " • $bulkFailed failed" else ""); Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { if (bulkTotal == 0) 0f else bulkDone.toFloat() / bulkTotal }, Modifier.fillMaxWidth()) }
                }
            } else restore?.let { state ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 28.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(state.fileName, maxLines = 1)
                        if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        else if (state.finishedPath != null) Text("Saved to ${state.finishedPath}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        else if (state.running) { Spacer(Modifier.height(6.dp)); LinearProgressIndicator(progress = { state.fraction }, Modifier.fillMaxWidth()); Text("${formatBytes(state.doneBytes)} of ${formatBytes(state.totalBytes)}", style = MaterialTheme.typography.bodySmall) }
                        if (!state.running) TextButton(onClick = { repository.clearRestoreState() }) { Text("Dismiss") }
                    }
                }
            }

            if (restorable.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (query.isBlank() && categoryFilter == null) "Nothing has been uploaded from this phone yet." else "No uploaded file matches that.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else LazyColumn(Modifier.weight(1f).padding(horizontal = 28.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)) {
                items(restorable, key = { it.id }) { record ->
                    RestoreRow(record, record.id in selected, restore?.running == true || bulkRunning, { if (record.id in selected) selected.remove(record.id) else selected.add(record.id) }, { scope.launch { runCatching { repository.restoreFile(record) } } })
                    HorizontalDivider()
                }
            }

            if (selected.isNotEmpty()) Button(onClick = {
                val targets = restorable.filter { it.id in selected }
                bulkRunning = true; bulkDone = 0; bulkFailed = 0; bulkTotal = targets.size
                scope.launch { for (record in targets) { runCatching { repository.restoreFile(record) }.onFailure { bulkFailed++ }; bulkDone++ }; bulkRunning = false; selected.clear() }
            }, enabled = !bulkRunning && restore?.running != true, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp), shape = RoundedCornerShape(28.dp)) { Text("Restore ${selected.size} selected") }
        }
    }
}

@Composable
private fun ReferenceFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, shape = RoundedCornerShape(11.dp), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onSurface, containerColor = MaterialTheme.colorScheme.background, labelColor = MaterialTheme.colorScheme.onSurfaceVariant), border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = MaterialTheme.colorScheme.outline, selectedBorderColor = MaterialTheme.colorScheme.secondaryContainer))
}

@Composable
private fun RestoreRow(record: FileRecord, checked: Boolean, busy: Boolean, onToggle: () -> Unit, onRestore: () -> Unit) {
    val context = LocalContext.current
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    var bitmap by remember(record.uri) { mutableStateOf(MediaThumbnails.peek(record)) }
    LaunchedEffect(record.uri) { if (bitmap == null) bitmap = MediaThumbnails.load(context, record) }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = !busy)
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = record.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: Text(extensionLabel(record.displayName), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(record.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text("${formatBytes(record.sizeBytes)} • ${record.uploadedAtMillis?.let { fmt.format(Date(it)) } ?: "uploaded"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onRestore, enabled = !busy) { Text("Restore") }
    }
}
