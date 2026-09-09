package com.airdrive.backup.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VideoFile
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
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val FILE_LIMIT = 1_000_000
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
    var selectedCategory by remember(initialCategory) { mutableStateOf(initialCategory) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var localState by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("newest") }
    var showFilters by remember { mutableStateOf(false) }
    val categoryName = selectedCategory?.name ?: ""

    val files by remember(query, categoryName, status, localState, sort) {
        dao.searchFlow(query.trim(), categoryName, status, localState, "", 0L, 0L, 0L, 0L, 0L, sort, FILE_LIMIT)
    }.collectAsState(initial = emptyList())

    val total by remember(query, categoryName, status, localState) {
        dao.searchCountFlow(query.trim(), categoryName, status, localState, "", 0L, 0L, 0L, 0L, 0L)
    }.collectAsState(initial = 0)

    val uploadedCount by remember(query, categoryName, localState) {
        dao.searchCountFlow(query.trim(), categoryName, "UPLOADED", localState, "", 0L, 0L, 0L, 0L, 0L)
    }.collectAsState(initial = 0)

    val pendingCount by remember(query, categoryName, localState) {
        dao.searchCountFlow(query.trim(), categoryName, "PENDING", localState, "", 0L, 0L, 0L, 0L, 0L)
    }.collectAsState(initial = 0)

    val uploaded = remember(files) { files.filter { it.status == UploadStatus.UPLOADED } }
    val pending = remember(files) { files.filter { it.status == UploadStatus.PENDING } }
    val other = remember(files) { files.filter { it.status != UploadStatus.UPLOADED && it.status != UploadStatus.PENDING } }

    Scaffold(
        containerColor = Color(0xFFF7F9FD),
        topBar = {
            TopAppBar(
                title = { Column { Text("Files", fontWeight = FontWeight.Bold); Text(if (selectedCategory == null) "All files in AirDrive" else categoryLabel(selectedCategory!!), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { showFilters = !showFilters }) { Icon(Icons.Default.FilterList, "Filters") } }
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
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear") } },
                placeholder = { Text("Search files, folders, extensions…") },
                supportingText = { Text("Searches names and stored paths • try .pdf, invoice, vacation") },
                shape = RoundedCornerShape(17.dp)
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fileFilters.forEach { filter ->
                    FilterChip(selected = selectedCategory == filter.category, onClick = { selectedCategory = filter.category }, label = { Text(filter.label) }, shape = RoundedCornerShape(13.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            StatusPills(status, uploadedCount, pendingCount) { status = it }
            if (showFilters) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
                Text("${Format.count(total)} files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(if (status == "UPLOADED") "Backed up" else if (status == "PENDING") "Waiting to upload" else "All categories", style = MaterialTheme.typography.labelSmall, color = Color(0xFF7C3AED))
            }
            if (files.isEmpty()) EmptyFiles(query)
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (status.isEmpty() && uploaded.isNotEmpty()) {
                    item { SectionHeader("Uploaded", uploadedCount, "Backed up to Telegram", Color(0xFF20A463)) }
                    items(uploaded, key = { it.id }) { record -> FileHubRow(record) { nav.navigate("${Routes.FILE_VIEWER}/${record.id}") } }
                }
                if (status.isEmpty() && pending.isNotEmpty()) {
                    item { SectionHeader("Pending upload", pendingCount, "Waiting for the next backup", Color(0xFFF59E0B)) }
                    items(pending, key = { it.id }) { record -> FileHubRow(record) { nav.navigate("${Routes.FILE_VIEWER}/${record.id}") } }
                }
                if (status.isNotEmpty()) items(files, key = { it.id }) { record -> FileHubRow(record) { nav.navigate("${Routes.FILE_VIEWER}/${record.id}") } }
                if (status.isEmpty() && other.isNotEmpty()) {
                    item { SectionHeader("Other status", other.size, "Needs attention", Color(0xFFE34D59)) }
                    items(other, key = { it.id }) { record -> FileHubRow(record) { nav.navigate("${Routes.FILE_VIEWER}/${record.id}") } }
                }
            }
        }
    }
}

@Composable
private fun StatusPills(selected: String, uploaded: Int, pending: Int, onSelected: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusPill("All", "", selected == "", onSelected, null)
        StatusPill("✓ ${Format.count(uploaded)}", "UPLOADED", selected == "UPLOADED", onSelected, Color(0xFF20A463))
        StatusPill("◷ ${Format.count(pending)}", "PENDING", selected == "PENDING", onSelected, Color(0xFFF59E0B))
    }
}

@Composable
private fun RowScope.StatusPill(label: String, value: String, selected: Boolean, onSelected: (String) -> Unit, accent: Color?) {
    FilterChip(selected = selected, onClick = { onSelected(value) }, label = { Text(label) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(15.dp), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent?.copy(alpha = 0.14f) ?: Color(0xFFE9D7FF), selectedLabelColor = accent ?: Color(0xFF5B21B6)))
}

@Composable
private fun SectionHeader(title: String, count: Int, subtitle: String, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(7.dp))
                Surface(shape = RoundedCornerShape(10.dp), color = accent.copy(alpha = 0.12f)) { Text(Format.count(count), Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileHubRow(record: FileRecord, onClick: () -> Unit) {
    val background = when (record.category) {
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
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilePreview(record, background, accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(record.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text("${categoryLabel(record.category)} • ${Format.bytes(record.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(if (record.status == UploadStatus.UPLOADED) Color(0xFF20A463) else accent))
                    Spacer(Modifier.width(5.dp))
                    Text(if (record.status == UploadStatus.UPLOADED) "Backed up to Telegram" else if (record.status == UploadStatus.PENDING) "Pending upload" else record.status.name.lowercase(Locale.getDefault()).replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = if (record.status == UploadStatus.UPLOADED) Color(0xFF20A463) else accent)
                }
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FilePreview(record: FileRecord, background: Color, accent: Color) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, record.uri, record.modifiedAtMillis) { value = withContext(Dispatchers.IO) { loadPreviewBitmap(context, record) } }
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(15.dp)).background(background), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else when (record.category) {
            BackupCategory.PHOTOS -> Icon(Icons.Default.ImageIcon, null, tint = accent, modifier = Modifier.size(30.dp))
            BackupCategory.VIDEOS -> Icon(Icons.Default.VideoFile, null, tint = accent, modifier = Modifier.size(30.dp))
            BackupCategory.AUDIO, BackupCategory.CALL_RECORDINGS -> Icon(Icons.Default.AudioFile, null, tint = accent, modifier = Modifier.size(30.dp))
            BackupCategory.PDFS, BackupCategory.WORD_EXCEL -> Icon(Icons.Default.Description, null, tint = accent, modifier = Modifier.size(30.dp))
            BackupCategory.OTHER_FILES -> Text(extensionLabel(record.displayName), color = accent, fontWeight = FontWeight.Bold)
        }
        if (record.status == UploadStatus.PENDING) Surface(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(19.dp), RoundedCornerShape(10.dp), color = Color.White) { Icon(Icons.Default.Upload, null, tint = Color(0xFFF59E0B), modifier = Modifier.padding(3.dp)) }
    }
}

private fun loadPreviewBitmap(context: android.content.Context, record: FileRecord): Bitmap? {
    val uri = Uri.parse(record.uri)
    if (uri.scheme.equals("file", true)) {
        val path = uri.path ?: return null
        if (!File(path).isFile) return null
        return when (record.category) {
            BackupCategory.PHOTOS -> BitmapFactory.decodeFile(path)
            BackupCategory.VIDEOS -> runCatching { MediaMetadataRetriever().let { r -> r.setDataSource(path); val b = r.getFrameAtTime(0); r.release(); b } }.getOrNull()
            else -> null
        }
    }
    if (uri.scheme.equals("content", true)) return when (record.category) {
        BackupCategory.PHOTOS -> runCatching { context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull()
        BackupCategory.VIDEOS -> runCatching { MediaMetadataRetriever().let { r -> r.setDataSource(context, uri); val b = r.getFrameAtTime(0); r.release(); b } }.getOrNull()
        else -> null
    }
    return null
}

@Composable
private fun EmptyFiles(query: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Search, null, tint = Color(0xFF7C3AED), modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp))
            Text(if (query.isBlank()) "No files yet" else "No matching files", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(if (query.isBlank()) "Connect Telegram and run a backup to populate your AirDrive inventory." else "Try another name, extension or category.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
