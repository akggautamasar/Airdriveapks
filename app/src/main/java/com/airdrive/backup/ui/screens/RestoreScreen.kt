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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Tune
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
import com.airdrive.backup.util.MediaThumbnails
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val RestoreBlue = Color(0xFF2F6FEA)
private val RestoreBlueLight = Color(0xFFDCEBFF)
private val RestorePurple = Color(0xFF7C3AED)
private val RestorePurpleLight = Color(0xFFF0DFFF)
private val RestoreGreen = Color(0xFF20A463)
private val RestoreGreenLight = Color(0xFFDDF6EC)
private val RestoreRed = Color(0xFFE84A5F)
private val RestoreRedLight = Color(0xFFFFE1E6)
private val RestoreOrange = Color(0xFFF59E0B)
private val RestoreOrangeLight = Color(0xFFFFEBD0)
private val RestoreCyan = Color(0xFF18B8C8)
private val RestoreBackground = Color(0xFFF7F9FD)

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
    LaunchedEffect(restorable) { selected.retainAll(restorable.map { it.id }.toSet()) }
    val allSelected = restorable.isNotEmpty() && selected.size == restorable.size
    DisposableEffect(Unit) { onDispose { MediaThumbnails.trim() } }

    Scaffold(
        containerColor = RestoreBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Restore",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF17213B)
                        )
                        Text(
                            "Get your files back, anytime",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF6E7788)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selected.isEmpty()) nav.popBackStack() else selected.clear()
                    }) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color(0xFF263246))
                    }
                },
                actions = {
                    if (restorable.isNotEmpty()) {
                        TextButton(onClick = {
                            if (allSelected) selected.clear()
                            else {
                                selected.clear()
                                selected.addAll(restorable.map { it.id })
                            }
                        }) {
                            Icon(Icons.Default.SelectAll, null, modifier = Modifier.size(18.dp), tint = RestoreBlue)
                            Spacer(Modifier.width(5.dp))
                            Text(if (allSelected) "Clear" else "Select all", color = RestoreBlue)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = RestoreBackground)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Primary restore paths: old phone -> new phone and Telegram -> this phone.
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RestoreSourceCard(
                    title = "Restore from\nOld Device",
                    subtitle = "Transfer files from your\nprevious device",
                    icon = Icons.Default.PhoneAndroid,
                    iconTint = RestoreBlue,
                    containerColor = RestoreBlueLight,
                    onClick = { nav.navigate(Routes.MIGRATE) }
                )
                RestoreSourceCard(
                    title = "Restore from\nTelegram",
                    subtitle = "Download your\nbacked up files",
                    icon = Icons.Default.CloudDownload,
                    iconTint = RestorePurple,
                    containerColor = RestorePurpleLight,
                    onClick = { /* already on Telegram restore */ }
                )
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Color(0xFF596579)) },
                placeholder = { Text("Search uploaded files…", color = Color(0xFF7B8494)) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.White,
                    focusedContainerColor = Color.White,
                    unfocusedBorderColor = Color(0xFFD9DEEA),
                    focusedBorderColor = RestoreBlue
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(62.dp)
                    .padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(12.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                RestoreFilterChip("All", categoryFilter == null, RestoreBlue, RestoreBlueLight) { categoryFilter = null }
                RestoreFilterChip("Photos", categoryFilter == BackupCategory.PHOTOS, RestoreGreen, RestoreGreenLight) {
                    categoryFilter = if (categoryFilter == BackupCategory.PHOTOS) null else BackupCategory.PHOTOS
                }
                RestoreFilterChip("Videos", categoryFilter == BackupCategory.VIDEOS, RestoreRed, RestoreRedLight) {
                    categoryFilter = if (categoryFilter == BackupCategory.VIDEOS) null else BackupCategory.VIDEOS
                }
                RestoreFilterChip("PDFs", categoryFilter == BackupCategory.PDFS, RestoreOrange, RestoreOrangeLight) {
                    categoryFilter = if (categoryFilter == BackupCategory.PDFS) null else BackupCategory.PDFS
                }
                RestoreFilterChip("Documents", categoryFilter == BackupCategory.WORD_EXCEL, RestorePurple, RestorePurpleLight) {
                    categoryFilter = if (categoryFilter == BackupCategory.WORD_EXCEL) null else BackupCategory.WORD_EXCEL
                }
            }

            Spacer(Modifier.height(10.dp))

            if (bulkRunning) {
                RestoreProgressCard(
                    title = "Restoring $bulkDone of $bulkTotal",
                    subtitle = if (bulkFailed > 0) "$bulkFailed failed" else "Please keep AirDrive open",
                    progress = if (bulkTotal == 0) 0f else bulkDone.toFloat() / bulkTotal
                )
            } else restore?.let { state ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(state.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        else if (state.finishedPath != null) Text("Saved to ${state.finishedPath}", color = RestoreGreen, style = MaterialTheme.typography.bodySmall)
                        else if (state.running) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                            Text("${formatBytes(state.doneBytes)} of ${formatBytes(state.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!state.running) TextButton(onClick = { repository.clearRestoreState() }) { Text("Dismiss") }
                    }
                }
            }

            Spacer(Modifier.height(2.dp))

            if (restorable.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(48.dp), tint = RestoreBlue.copy(alpha = .65f))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (query.isBlank() && categoryFilter == null) "Nothing has been uploaded from this phone yet."
                            else "No uploaded file matches that.",
                            color = Color(0xFF6E7788)
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(restorable, key = { it.id }) { record ->
                        RestoreRow(
                            record = record,
                            checked = record.id in selected,
                            busy = restore?.running == true || bulkRunning,
                            onToggle = {
                                if (record.id in selected) selected.remove(record.id) else selected.add(record.id)
                            },
                            onRestore = { scope.launch { runCatching { repository.restoreFile(record) } } }
                        )
                    }
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
                                runCatching { repository.restoreFile(record) }.onFailure { bulkFailed++ }
                                bulkDone++
                            }
                            bulkRunning = false
                            selected.clear()
                        }
                    },
                    enabled = !bulkRunning && restore?.running != true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RestoreBlue)
                ) {
                    Icon(Icons.Default.CloudDownload, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Restore ${selected.size} selected", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun RestoreSourceCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    containerColor: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(190.dp).height(148.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = .82f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = iconTint, modifier = Modifier.size(28.dp))
                }
            }
            Column(Modifier.padding(top = 58.dp).fillMaxWidth().padding(end = 26.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF17213B))
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF4F5D78))
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).size(36.dp),
                shape = RoundedCornerShape(50),
                color = Color.White.copy(alpha = .9f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowForward, null, tint = iconTint, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun RestoreProgressCard(title: String, subtitle: String, progress: Float) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = RestoreBlueLight)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudDownload, null, tint = RestoreBlue)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, color = Color(0xFF17213B))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF52627D))
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RestoreFilterChip(
    label: String,
    selected: Boolean,
    tint: Color,
    selectedBackground: Color,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        shape = RoundedCornerShape(14.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = selectedBackground,
            selectedLabelColor = tint,
            containerColor = Color.White,
            labelColor = Color(0xFF657084)
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Color(0xFFD9DEEA),
            selectedBorderColor = tint
        )
    )
}

@Composable
private fun RestoreRow(
    record: FileRecord,
    checked: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onRestore: () -> Unit
) {
    val context = LocalContext.current
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    var bitmap by remember(record.uri) { mutableStateOf(MediaThumbnails.peek(record)) }
    LaunchedEffect(record.uri) { if (bitmap == null) bitmap = MediaThumbnails.load(context, record) }
    val accent = categoryAccent(record.category)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = !busy)
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(13.dp)).background(accent.copy(alpha = .12f)),
                contentAlignment = Alignment.Center
            ) {
                bitmap?.let {
                    Image(bitmap = it.asImageBitmap(), contentDescription = record.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } ?: Icon(categoryIcon(record.category), null, tint = accent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(record.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatBytes(record.sizeBytes)} • ${record.uploadedAtMillis?.let { fmt.format(Date(it)) } ?: "uploaded"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7A8494)
                )
                Spacer(Modifier.height(4.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = accent.copy(alpha = .12f)) {
                    Row(Modifier.padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(categoryIcon(record.category), null, tint = accent, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(categoryLabel(record.category), style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = onRestore,
                enabled = !busy,
                shape = RoundedCornerShape(13.dp),
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 9.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RestoreBlue)
            ) {
                Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("Restore")
            }
        }
    }
}

private fun categoryAccent(category: BackupCategory): Color = when (category) {
    BackupCategory.PHOTOS -> RestoreGreen
    BackupCategory.VIDEOS -> RestoreRed
    BackupCategory.PDFS -> RestoreOrange
    BackupCategory.WORD_EXCEL -> RestorePurple
    BackupCategory.AUDIO -> RestoreCyan
    BackupCategory.CALL_RECORDINGS -> RestoreBlue
    BackupCategory.OTHER_FILES -> Color(0xFF64748B)
}

private fun categoryIcon(category: BackupCategory): androidx.compose.ui.graphics.vector.ImageVector = when (category) {
    BackupCategory.PHOTOS -> Icons.Default.Image
    BackupCategory.VIDEOS -> Icons.Default.PlayArrow
    BackupCategory.PDFS, BackupCategory.WORD_EXCEL, BackupCategory.OTHER_FILES -> Icons.Default.Description
    BackupCategory.AUDIO -> Icons.Default.PlayArrow
    BackupCategory.CALL_RECORDINGS -> Icons.Default.PhoneAndroid
}
