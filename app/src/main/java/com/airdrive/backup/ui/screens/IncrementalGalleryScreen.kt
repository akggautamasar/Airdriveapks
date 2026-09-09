package com.airdrive.backup.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
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
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.Format
import com.airdrive.backup.util.MediaThumbnails
import kotlinx.coroutines.flow.collect

private enum class GalleryKind(val label: String, val categories: List<BackupCategory>) {
    ALL("All", listOf(BackupCategory.PHOTOS, BackupCategory.VIDEOS)),
    PHOTOS("Photos", listOf(BackupCategory.PHOTOS)),
    VIDEOS("Videos", listOf(BackupCategory.VIDEOS))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncrementalGalleryScreen(nav: NavHostController, initialKind: String = "all") {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val gridState = rememberLazyGridState()
    var kind by remember(initialKind) { mutableStateOf(when (initialKind.lowercase()) { "photos" -> GalleryKind.PHOTOS; "videos" -> GalleryKind.VIDEOS; else -> GalleryKind.ALL }) }
    var onlyBackedUp by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var limit by remember { mutableIntStateOf(GALLERY_PAGE) }
    var loadingMore by remember { mutableStateOf(false) }

    val categoryNames = remember(kind) { kind.categories.map { it.name } }
    val categoryName = if (kind == GalleryKind.PHOTOS) BackupCategory.PHOTOS.name else if (kind == GalleryKind.VIDEOS) BackupCategory.VIDEOS.name else ""
    val files by remember(categoryNames, query, onlyBackedUp, limit) { dao.galleryFlow(categoryNames, query.trim(), onlyBackedUp, limit) }.collectAsState(initial = emptyList())
    val total by remember(categoryNames, onlyBackedUp) { dao.galleryCountFlow(categoryNames, onlyBackedUp) }.collectAsState(initial = 0)
    val uploadedCount by remember(categoryName) { dao.searchCountFlow("", categoryName, "UPLOADED", "", "", 0L, 0L, 0L, 0L, 0L) }.collectAsState(initial = 0)
    val pendingCount by remember(categoryName) { dao.searchCountFlow("", categoryName, "PENDING", "", "", 0L, 0L, 0L, 0L, 0L) }.collectAsState(initial = 0)

    LaunchedEffect(kind, onlyBackedUp, query) { limit = GALLERY_PAGE; loadingMore = false; gridState.scrollToItem(0) }
    LaunchedEffect(files.size, limit, total) { if (files.size >= minOf(limit, total) || files.size >= total) loadingMore = false }
    LaunchedEffect(gridState, files.size, total) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { lastIndex ->
            if (lastIndex >= (files.size - LOAD_AHEAD).coerceAtLeast(0) && !loadingMore && files.size < total) { loadingMore = true; limit += GALLERY_PAGE }
        }
    }
    DisposableEffect(Unit) { onDispose { MediaThumbnails.trim() } }

    Scaffold(containerColor = Color(0xFFF8F9FD)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TopAppBar(
                title = { Column { Text(if (kind == GalleryKind.PHOTOS) "Photos" else if (kind == GalleryKind.VIDEOS) "Videos" else "Photos", fontWeight = FontWeight.Bold); Text("${Format.count(total)} files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { searchVisible = !searchVisible }) { Icon(Icons.Default.Search, "Search") }; IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "More") } }
            )
            if (searchVisible) {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search photos and videos") }, shape = RoundedCornerShape(16.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GalleryStatusChip("All", total, true, Purple) { onlyBackedUp = false }
                GalleryStatusChip("Uploaded", uploadedCount, onlyBackedUp, Green) { onlyBackedUp = true }
                GalleryStatusChip("Pending", pendingCount, false, Orange) { onlyBackedUp = false }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GalleryTab("All", kind == GalleryKind.ALL) { kind = GalleryKind.ALL }
                GalleryTab("Photos", kind == GalleryKind.PHOTOS) { kind = GalleryKind.PHOTOS }
                GalleryTab("Videos", kind == GalleryKind.VIDEOS) { kind = GalleryKind.VIDEOS }
            }
            if (files.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (query.isBlank()) "No photos or videos found" else "No media matches that search", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else LazyVerticalGrid(columns = GridCells.Fixed(3), state = gridState, modifier = Modifier.weight(1f).padding(horizontal = 10.dp), contentPadding = PaddingValues(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(files, key = { it.id }) { record -> IncrementalMediaCell(record) { nav.navigate("${Routes.FILE_VIEWER}/${record.id}") } }
                if (loadingMore) item { Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) } }
            }
        }
    }
}

@Composable private fun GalleryTab(label: String, selected: Boolean, onClick: () -> Unit) { Surface(Modifier.clickable(onClick = onClick), RoundedCornerShape(12.dp), color = if (selected) Color(0xFFF0EBFF) else Color.White) { Text(label, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = if (selected) Purple else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.labelMedium) } }
@Composable private fun GalleryStatusChip(label: String, count: Int, selected: Boolean, accent: Color, onClick: () -> Unit) { Surface(Modifier.weight(1f).clickable(onClick = onClick), RoundedCornerShape(14.dp), color = if (selected) accent.copy(alpha = .09f) else Color.White, border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .45f)) else null) { Row(Modifier.padding(horizontal = 9.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) { Icon(if (label == "Uploaded") Icons.Default.CheckCircle else if (label == "Pending") Icons.Default.Schedule else Icons.Default.GridView, null, tint = accent, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Column { Text(label, style = MaterialTheme.typography.labelSmall); Text(Format.count(count), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) } } } }

@Composable private fun IncrementalMediaCell(record: FileRecord, onClick: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(record.uri) { mutableStateOf(MediaThumbnails.peek(record)) }
    var duration by remember(record.uri) { mutableStateOf(record.durationMillis) }
    LaunchedEffect(record.uri) { if (bitmap == null) bitmap = MediaThumbnails.load(context, record) }
    LaunchedEffect(record.uri) { if (record.category == BackupCategory.VIDEOS && duration == null) MediaThumbnails.duration(record)?.takeIf { it > 0 }?.let { duration = it } }
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
        bitmap?.let { Image(it.asImageBitmap(), record.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (record.category == BackupCategory.VIDEOS) Surface(color = Color.Black.copy(alpha = .35f), shape = CircleShape, modifier = Modifier.align(Alignment.Center)) { Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.padding(7.dp).size(24.dp)) }
        if (record.status.name == "UPLOADED") Surface(color = Color.White, shape = CircleShape, modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).size(22.dp)) { Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.padding(2.dp)) }
        if (record.status.name == "PENDING") Surface(color = Color.White, shape = CircleShape, modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).size(22.dp)) { Icon(Icons.Default.Schedule, null, tint = Orange, modifier = Modifier.padding(2.dp)) }
        duration?.takeIf { it > 0 }?.let { Surface(color = Color.Black.copy(alpha = .58f), shape = RoundedCornerShape(5.dp), modifier = Modifier.align(Alignment.BottomStart).padding(5.dp)) { Text(Format.duration(it), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)) } }
    }
}

private val Purple = Color(0xFF6D4AFF)
private val Green = Color(0xFF18A86B)
private val Orange = Color(0xFFF2A20B)
private const val GALLERY_PAGE = 300
private const val LOAD_AHEAD = 36
