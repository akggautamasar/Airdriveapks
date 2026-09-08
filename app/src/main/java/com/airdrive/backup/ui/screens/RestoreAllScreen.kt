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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.util.Format
import com.airdrive.backup.util.MediaThumbnails
import kotlinx.coroutines.launch
import java.util.Locale

private val UBlue = Color(0xFF2F6FEA)
private val UBlueLight = Color(0xFFDCEBFF)
private val UPurple = Color(0xFF7C3AED)
private val UPurpleLight = Color(0xFFF0DFFF)
private val UGreen = Color(0xFF20A463)
private val UGreenLight = Color(0xFFDDF6EC)
private val UBg = Color(0xFFF7F9FD)
private val UText = Color(0xFF17213B)
private val USub = Color(0xFF6E7788)

/** Restore UI with one selection model: Select all means the complete matching library, not a page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreAllScreen(nav: NavHostController) {
    val context = LocalContext.current
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<BackupCategory?>(null) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectAll by remember { mutableStateOf(false) }
    var excludedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(0) }
    var totalStarted by remember { mutableStateOf(0) }

    // Int.MAX_VALUE deliberately removes the old 200/5000 UI ceiling. LazyColumn still only
    // composes visible rows, while the selection represents every matching record.
    val records by remember(query, category) {
        repository.restorableFlow(query.trim(), category?.name ?: "", Int.MAX_VALUE)
    }.collectAsState(initial = emptyList())
    val restore by repository.restoreState.collectAsState()

    LaunchedEffect(query, category) {
        selectedIds = emptySet()
        selectAll = false
        excludedIds = emptySet()
    }

    val allSelected = selectAll && excludedIds.isEmpty()
    val selectedCount = if (selectAll) records.size - excludedIds.size else selectedIds.size

    DisposableEffect(Unit) { onDispose { MediaThumbnails.trim() } }

    Scaffold(
        containerColor = UBg,
        topBar = {
            TopAppBar(
                title = { Column { Text("Restore", fontWeight = FontWeight.Bold, color = UText); Text("Bring everything back in one go", style = MaterialTheme.typography.bodySmall, color = USub) } },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    if (records.isNotEmpty()) TextButton(onClick = {
                        if (allSelected) { selectAll = false; excludedIds = emptySet(); selectedIds = emptySet() }
                        else { selectAll = true; excludedIds = emptySet(); selectedIds = emptySet() }
                    }) { Icon(Icons.Default.SelectAll, null, tint = UBlue, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(5.dp)); Text(if (allSelected) "Clear all" else "Select all", color = UBlue) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = UBg)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RestoreSourceCard("Old device", "Whole library • resumable", Icons.Default.PhoneAndroid, UBlue, UBlueLight) { nav.navigate(Routes.MIGRATE) }
                RestoreSourceCard("Telegram", "All cloud copies", Icons.Default.CloudDownload, UPurple, UPurpleLight) { }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search uploaded files…") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(60.dp)
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RestoreChip("All", category == null) { category = null }
                RestoreChip("Photos", category == BackupCategory.PHOTOS) { category = BackupCategory.PHOTOS }
                RestoreChip("Videos", category == BackupCategory.VIDEOS) { category = BackupCategory.VIDEOS }
                RestoreChip("PDFs", category == BackupCategory.PDFS) { category = BackupCategory.PDFS }
                RestoreChip("Documents", category == BackupCategory.WORD_EXCEL) { category = BackupCategory.WORD_EXCEL }
                RestoreChip("Audio", category == BackupCategory.AUDIO) { category = BackupCategory.AUDIO }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${Format.count(records.size)} available", color = USub, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (selectedCount > 0) Text("${Format.count(selectedCount)} selected", color = UBlue, fontWeight = FontWeight.Bold)
            }
            restore?.let { state ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = if (state.error == null) UBlueLight else Color(0xFFFFE1E6))) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (state.error != null) "Restore failed" else if (state.finishedPath != null) "Restore complete" else "Restoring ${state.fileName}…", fontWeight = FontWeight.Bold, color = UText)
                        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        if (state.running) LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        state.finishedPath?.let { Text("Saved to $it", style = MaterialTheme.typography.bodySmall, color = UGreen) }
                    }
                }
            }
            if (records.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (query.isBlank()) "No uploaded files to restore" else "No files match your search", color = USub) }
            } else {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 4.dp, bottom = if (selectedCount > 0) 88.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(records, key = { it.id }) { record ->
                        val checked = if (selectAll) record.id !in excludedIds else record.id in selectedIds
                        RestoreAllRow(record, checked, running || restore?.running == true) {
                            if (selectAll) {
                                excludedIds = if (checked) excludedIds + record.id else excludedIds - record.id
                            } else {
                                selectedIds = if (checked) selectedIds - record.id else selectedIds + record.id
                            }
                        }
                    }
                }
            }
            if (selectedCount > 0) {
                Button(
                    onClick = {
                        val targets = if (selectAll) records.filterNot { it.id in excludedIds } else records.filter { it.id in selectedIds }
                        running = true; done = 0; failed = 0; totalStarted = targets.size
                        scope.launch {
                            for (record in targets) {
                                runCatching { repository.restoreFile(record) }.onFailure { failed++ }
                                done++
                            }
                            running = false; selectedIds = emptySet(); selectAll = false; excludedIds = emptySet()
                        }
                    },
                    enabled = !running && restore?.running != true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = UBlue)
                ) {
                    Icon(Icons.Default.CloudDownload, null); Spacer(Modifier.width(8.dp))
                    Text(if (running) "Restoring $done / $totalStarted" else "Restore ${Format.count(selectedCount)} selected", fontWeight = FontWeight.Bold)
                }
            }
            if (!running && failed > 0) Text("$failed files failed; the rest were restored.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
        }
    }
}

@Composable private fun RestoreSourceCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, bg: Color, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.width(190.dp).height(132.dp), shape = RoundedCornerShape(21.dp), colors = CardDefaults.cardColors(containerColor = bg), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(Modifier.padding(15.dp)) {
            Surface(Modifier.size(44.dp), RoundedCornerShape(14.dp), color = Color.White.copy(alpha = .86f)) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp)) } }
            Spacer(Modifier.height(8.dp)); Text(title, fontWeight = FontWeight.Bold, color = UText, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = USub)
        }
    }
}

@Composable private fun RestoreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, shape = RoundedCornerShape(14.dp))
}

@Composable private fun RestoreAllRow(record: FileRecord, checked: Boolean, busy: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(record.uri) { mutableStateOf(MediaThumbnails.peek(record)) }
    LaunchedEffect(record.uri) { if (bitmap == null) bitmap = MediaThumbnails.load(context, record) }
    val accent = when (record.category) { BackupCategory.PHOTOS -> UGreen; BackupCategory.VIDEOS -> Color(0xFFE84A5F); BackupCategory.PDFS -> Color(0xFFF59E0B); BackupCategory.WORD_EXCEL -> UPurple; BackupCategory.AUDIO -> Color(0xFF18B8C8); BackupCategory.CALL_RECORDINGS -> UBlue; BackupCategory.OTHER_FILES -> Color(0xFF18B8C8) }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, { onToggle() }, enabled = !busy)
            Box(Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                bitmap?.let { Image(it.asImageBitmap(), record.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    ?: Text(record.displayName.substringAfterLast('.', "FILE").uppercase(Locale.getDefault()).take(5), color = accent, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) {
                Text(record.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = UText)
                Text("${Format.bytes(record.sizeBytes)} • ${categoryLabel(record.category)}", style = MaterialTheme.typography.bodySmall, color = USub)
            }
        }
    }
}
