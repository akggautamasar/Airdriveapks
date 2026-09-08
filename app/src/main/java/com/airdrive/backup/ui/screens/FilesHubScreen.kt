package com.airdrive.backup.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.LocalState
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.util.Format
import com.airdrive.backup.util.Sharing
import kotlinx.coroutines.launch
import java.util.Locale

private const val FILE_LIMIT = 1000000

private data class FileFilter(val label: String, val category: BackupCategory?)

private val fileFilters = listOf(
    FileFilter("All", null),
    FileFilter("Photos", BackupCategory.PHOTOS),
    FileFilter("Videos", BackupCategory.VIDEOS),
    FileFilter("PDFs", BackupCategory.PDFS),
    FileFilter("Documents", BackupCategory.WORD_EXCEL),
    FileFilter("Audio", BackupCategory.AUDIO),
    FileFilter("Calls", BackupCategory.CALL_RECORDINGS),
    FileFilter("Other", BackupCategory.OTHER_FILES)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesHubScreen(nav: NavHostController, initialCategory: BackupCategory? = null) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()

    var selectedCategory by remember(initialCategory) { mutableStateOf(initialCategory) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var localState by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("newest") }
    var showFilters by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<FileRecord?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val categoryName = selectedCategory?.name ?: ""
    val files by remember(query, categoryName, status, localState, sort) {
        dao.searchFlow(
            query = query.trim(),
            categoryName = categoryName,
            statusName = status,
            localStateName = localState,
            folder = "",
            chatId = 0L,
            minBytes = 0L,
            maxBytes = 0L,
            fromMillis = 0L,
            toMillis = 0L,
            sort = sort,
            limit = FILE_LIMIT
        )
    }.collectAsState(initial = emptyList())

    val total by remember(query, categoryName, status, localState) {
        dao.searchCountFlow(query.trim(), categoryName, status, localState, "", 0L, 0L, 0L, 0L, 0L)
    }.collectAsState(initial = 0)

    Scaffold(
        containerColor = Color(0xFFF7F9FD),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Files", fontWeight = FontWeight.Bold)
                        Text(
                            if (selectedCategory == null) "All files in AirDrive" else categoryLabel(selectedCategory!!),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showFilters = !showFilters }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filters")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear") }
                },
                placeholder = { Text("Search files, folders, extensions…") },
                supportingText = {
                    Text("Searches file names and stored paths. Try: .pdf, invoice, vacation, telegram")
                },
                shape = RoundedCornerShape(16.dp)
            )

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                fileFilters.forEach { filter ->
                    FilterChip(
                        selected = selectedCategory == filter.category,
                        onClick = { selectedCategory = filter.category },
                        label = { Text(filter.label) },
                        shape = RoundedCornerShape(13.dp)
                    )
                }
            }

            if (showFilters) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Status", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("" to "Any", "UPLOADED" to "Backed up", "PENDING" to "Pending", "FAILED" to "Failed").forEach { (value, label) ->
                            FilterChip(selected = status == value, onClick = { status = value }, label = { Text(label) })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Device availability", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("" to "Any", "PRESENT" to "On device", "MISSING" to "Cloud only", "FREED" to "Freed").forEach { (value, label) ->
                            FilterChip(selected = localState == value, onClick = { localState = value }, label = { Text(label) })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Sort", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("newest" to "Newest", "oldest" to "Oldest", "largest" to "Largest", "smallest" to "Smallest", "name" to "A–Z").forEach { (value, label) ->
                            FilterChip(selected = sort == value, onClick = { sort = value }, label = { Text(label) })
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${Format.count(files.size)} of ${Format.count(total)} files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (selectedCategory == null) Text("All categories", style = MaterialTheme.typography.labelSmall, color = Color(0xFF7C3AED))
            }

            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Text(if (query.isBlank()) "No files found" else "No files match your search", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("Try another name, extension, category or filter.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(files, key = { it.id }) { record ->
                        FileHubRow(record) { selected = record; message = null }
                    }
                }
            }
        }
    }

    selected?.let { record ->
        val local = record.localState != LocalState.MISSING && record.localState != LocalState.FREED &&
            (record.uri.startsWith("file://") || record.uri.startsWith("content://"))
        val canRestore = record.status == UploadStatus.UPLOADED && record.telegramMessageId != null
        AlertDialog(
            onDismissRequest = { if (!busy) selected = null },
            title = { Text(record.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${categoryLabel(record.category)} • ${Format.bytes(record.sizeBytes)}")
                    Text("${record.status.name.lowercase(Locale.getDefault()).replace('_', ' ')} • ${if (local) "on device" else "cloud copy"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${record.displayName.substringAfterLast('.', "file").uppercase(Locale.getDefault())} file", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
            confirmButton = {
                Row {
                    if (local) TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            val staged = Sharing.stage(context, Uri.parse(record.uri), record.displayName)
                            message = if (staged != null && Sharing.open(context, staged, record.displayName)) "Opening ${record.displayName}" else "Could not open this file."
                            busy = false; selected = null
                        }
                    }) { Text("Open") }
                    if (canRestore) TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            runCatching { repository.restoreFile(record) }
                                .onSuccess { message = "Restored ${record.displayName} to ${it.parent}" }
                                .onFailure { message = "Could not restore it: ${it.message?.take(160)}" }
                            busy = false; selected = null
                        }
                    }) { Text(if (local) "Restore copy" else "Restore") }
                }
            }
        )
    }
}

@Composable
private fun FileHubRow(record: FileRecord, onClick: () -> Unit) {
    val tint = when (record.category) {
        BackupCategory.PHOTOS -> Color(0xFFEAF2FF)
        BackupCategory.VIDEOS -> Color(0xFFFFF4DD)
        BackupCategory.PDFS -> Color(0xFFE2F8FA)
        BackupCategory.WORD_EXCEL -> Color(0xFFE5F7EE)
        BackupCategory.AUDIO -> Color(0xFFF0E8FF)
        BackupCategory.CALL_RECORDINGS -> Color(0xFFFFE8EA)
        BackupCategory.OTHER_FILES -> Color(0xFFEAF2FF)
    }
    val accent = when (record.category) {
        BackupCategory.PHOTOS -> Color(0xFF2F6FEA)
        BackupCategory.VIDEOS -> Color(0xFFF59E0B)
        BackupCategory.PDFS -> Color(0xFF18B8C8)
        BackupCategory.WORD_EXCEL -> Color(0xFF20A463)
        BackupCategory.AUDIO -> Color(0xFF7C3AED)
        BackupCategory.CALL_RECORDINGS -> Color(0xFFE34D59)
        BackupCategory.OTHER_FILES -> Color(0xFF2F6FEA)
    }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(13.dp)).background(tint), contentAlignment = Alignment.Center) {
                Text(extensionLabel(record.displayName), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(record.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    "${categoryLabel(record.category)} • ${Format.bytes(record.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (record.status == UploadStatus.UPLOADED) "Backed up to Telegram" else record.status.name.lowercase(Locale.getDefault()).replace('_', ' '),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (record.status == UploadStatus.UPLOADED) Color(0xFF20A463) else accent
                )
            }
        }
    }
}
