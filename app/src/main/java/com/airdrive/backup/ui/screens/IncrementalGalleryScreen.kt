package com.airdrive.backup.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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
    ALL("Photos & videos", listOf(BackupCategory.PHOTOS, BackupCategory.VIDEOS)),
    PHOTOS("Photos", listOf(BackupCategory.PHOTOS)),
    VIDEOS("Videos", listOf(BackupCategory.VIDEOS))
}

/**
 * Gallery uses the same FileViewerScreen as Files.
 * The viewer is pushed on top of Gallery, so Back returns to this exact
 * Gallery instance with its current search, filter and scroll position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncrementalGalleryScreen(nav: NavHostController) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val gridState = rememberLazyGridState()
    var kind by remember { mutableStateOf(GalleryKind.ALL) }
    var onlyBackedUp by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(GALLERY_PAGE) }
    var loadingMore by remember { mutableStateOf(false) }

    val categoryNames = remember(kind) { kind.categories.map { it.name } }
    val files by remember(categoryNames, query, onlyBackedUp, limit) {
        dao.galleryFlow(categoryNames, query.trim(), onlyBackedUp, limit)
    }.collectAsState(initial = emptyList())
    val total by remember(categoryNames, onlyBackedUp) {
        dao.galleryCountFlow(categoryNames, onlyBackedUp)
    }.collectAsState(initial = 0)

    LaunchedEffect(kind, onlyBackedUp, query) {
        limit = GALLERY_PAGE
        loadingMore = false
        gridState.scrollToItem(0)
    }

    LaunchedEffect(files.size, limit, total) {
        if (files.size >= minOf(limit, total) || files.size >= total) loadingMore = false
    }

    LaunchedEffect(gridState, files.size, total) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastIndex ->
                val nearEnd = lastIndex >= (files.size - LOAD_AHEAD).coerceAtLeast(0)
                if (nearEnd && !loadingMore && files.size < total) {
                    loadingMore = true
                    limit += GALLERY_PAGE
                }
            }
    }

    DisposableEffect(Unit) { onDispose { MediaThumbnails.trim() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Gallery", fontWeight = FontWeight.Bold)
                        Text("Your photos and videos, loaded as you scroll", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GalleryKind.values().forEach { option ->
                    FilterChip(selected = kind == option, onClick = { kind = option }, label = { Text(option.label) })
                }
                FilterChip(selected = onlyBackedUp, onClick = { onlyBackedUp = !onlyBackedUp }, label = { Text("Backed up only") })
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search by file name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${Format.count(files.size)} of ${Format.count(total)} loaded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (loadingMore) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Loading more…", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (query.isBlank()) "No photos or videos found" else "No media matches that search", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    state = gridState,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(files, key = { it.id }) { record ->
                        IncrementalMediaCell(record) {
                            // Push the production viewer/player. The current Gallery
                            // destination remains underneath it in the back stack.
                            nav.navigate("${Routes.FILE_VIEWER}/${record.id}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IncrementalMediaCell(record: FileRecord, onClick: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(record.uri) { mutableStateOf(MediaThumbnails.peek(record)) }
    var duration by remember(record.uri) { mutableStateOf(record.durationMillis) }
    LaunchedEffect(record.uri) { if (bitmap == null) bitmap = MediaThumbnails.load(context, record) }
    LaunchedEffect(record.uri) {
        if (record.category == BackupCategory.VIDEOS && duration == null) {
            MediaThumbnails.duration(record)?.takeIf { it > 0 }?.let { duration = it }
        }
    }
    Box(
        Modifier.aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        bitmap?.let { Image(it.asImageBitmap(), record.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (record.category == BackupCategory.VIDEOS) {
            Surface(color = Color.Black.copy(alpha = .38f), shape = RoundedCornerShape(50), modifier = Modifier.align(Alignment.Center)) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.padding(5.dp).size(24.dp))
            }
        }
        duration?.takeIf { it > 0 }?.let {
            Surface(color = Color.Black.copy(alpha = .55f), shape = RoundedCornerShape(4.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                Text(Format.duration(it), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
    }
}

private const val GALLERY_PAGE = 300
private const val LOAD_AHEAD = 36
