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
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.LocalState
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.util.Format
import com.airdrive.backup.util.MediaThumbnails
import com.airdrive.backup.util.Sharing
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The three ways to slice the media feed; the labels are what the chips read. */
private enum class MediaKind(val label: String, val categories: List<BackupCategory>) {
    ALL("Photos & videos", listOf(BackupCategory.PHOTOS, BackupCategory.VIDEOS)),
    PHOTOS("Photos", listOf(BackupCategory.PHOTOS)),
    VIDEOS("Videos", listOf(BackupCategory.VIDEOS))
}

/** One flat list drives the grid: headers span the full row, media take one cell each. */
private sealed interface GalleryEntry {
    data class MonthHeader(val label: String, val count: Int) : GalleryEntry
    data class Media(val record: FileRecord) : GalleryEntry
}

/** Enough for years of camera history without holding the whole table in memory. */
private const val GALLERY_LIMIT = 600

/**
 * Wishlist items 6 and 7: a real gallery instead of a list of file names, grouped by month, with
 * video thumbnails and lengths rather than a generic film icon.
 *
 * Two deliberate choices are worth knowing about. Month headers are cut in Kotlin, not in SQL,
 * because SQLite's strftime works in UTC and would file a late-evening photo under the wrong
 * month for most of the world. And durations are read once per video and written back to the row
 * ([FileRecord.durationMillis]), because pulling one costs a native decoder open — the second visit
 * to the gallery should be free.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(nav: NavHostController) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(MediaKind.ALL) }
    var onlyBackedUp by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<FileRecord?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val categoryNames = remember(kind) { kind.categories.map { it.name } }
    val files by remember(categoryNames, query, onlyBackedUp) {
        dao.galleryFlow(categoryNames, query.trim(), onlyBackedUp, GALLERY_LIMIT)
    }.collectAsState(initial = emptyList())
    val total by remember(categoryNames, onlyBackedUp) {
        dao.galleryCountFlow(categoryNames, onlyBackedUp)
    }.collectAsState(initial = 0)
    val restore by repository.restoreState.collectAsState()

    val entries = remember(files) { groupByMonth(files) }

    // Bitmaps are the largest thing this screen holds. Leaving the gallery gives most of that
    // memory back; what stays is enough to make coming straight back feel instant.
    DisposableEffect(Unit) { onDispose { MediaThumbnails.trim() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gallery") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (option in MediaKind.values()) {
                    FilterChip(
                        selected = kind == option,
                        onClick = { kind = option },
                        label = { Text(option.label) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                FilterChip(
                    selected = onlyBackedUp,
                    onClick = { onlyBackedUp = !onlyBackedUp },
                    label = { Text("Backed up only") }
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search by file name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Text(
                if (files.size >= GALLERY_LIMIT) {
                    "Newest ${Format.count(files.size)} of ${Format.count(total)}"
                } else {
                    "${Format.count(files.size)} of ${Format.count(total)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            restore?.let { state ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    when {
                        state.error != null -> Text(
                            "Restore failed: ${state.error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        state.finishedPath != null -> Text(
                            "Saved to ${state.finishedPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        else -> {
                            Text(
                                "Restoring ${state.fileName}…",
                                style = MaterialTheme.typography.bodySmall
                            )
                            LinearProgressIndicator(
                                progress = { state.fraction },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (entries.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when {
                            query.isNotBlank() -> "No photos or videos match that."
                            onlyBackedUp -> "Nothing in this category has been backed up yet."
                            else -> "No photos or videos have been scanned yet."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp)
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        items = entries,
                        key = { entry ->
                            when (entry) {
                                is GalleryEntry.MonthHeader -> "header:${entry.label}"
                                is GalleryEntry.Media -> "media:${entry.record.id}"
                            }
                        },
                        span = { entry ->
                            if (entry is GalleryEntry.MonthHeader) {
                                GridItemSpan(maxLineSpan)
                            } else {
                                GridItemSpan(1)
                            }
                        }
                    ) { entry ->
                        when (entry) {
                            is GalleryEntry.MonthHeader -> MonthHeaderRow(entry)
                            is GalleryEntry.Media -> MediaCell(
                                record = entry.record,
                                onClick = { selected = entry.record; message = null },
                                onDuration = { ms ->
                                    scope.launch {
                                        runCatching { dao.setDuration(entry.record.id, ms) }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    selected?.let { record ->
        MediaPreviewDialog(
            record = record,
            busy = busy,
            onDismiss = { selected = null },
            onRestore = {
                busy = true
                message = null
                scope.launch {
                    runCatching { repository.restoreFile(record) }
                        .onSuccess { message = "Restored ${record.displayName} to ${it.parent}" }
                        .onFailure { message = "Could not restore it: ${it.message?.take(160)}" }
                    busy = false
                    selected = null
                }
            },
            onShare = { openInstead ->
                busy = true
                message = null
                scope.launch {
                    val staged = Sharing.stage(context, record.localUri(), record.displayName)
                    if (staged == null) {
                        message = "That file is not on this phone any more — restore it first."
                    } else {
                        val handled = if (openInstead) {
                            Sharing.open(context, staged, record.displayName)
                        } else {
                            Sharing.share(context, staged, record.displayName)
                        }
                        if (!handled) message = "No app on this phone can handle that file."
                    }
                    busy = false
                }
            }
        )
    }
}

@Composable
private fun MonthHeaderRow(header: GalleryEntry.MonthHeader) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp, start = 4.dp)) {
        Text(
            header.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            if (header.count == 1) "1 item" else "${Format.count(header.count)} items",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One square cell. The thumbnail is loaded off the main thread on first composition and cached, so
 * scrolling back up does not decode anything twice. A video's length is read once and handed to
 * [onDuration] to be written to the row.
 */
@Composable
private fun MediaCell(
    record: FileRecord,
    onClick: () -> Unit,
    onDuration: (Long) -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(record.uri) { mutableStateOf(MediaThumbnails.peek(record)) }
    var duration by remember(record.uri) { mutableStateOf(record.durationMillis) }

    LaunchedEffect(record.uri) {
        if (bitmap == null) bitmap = MediaThumbnails.load(context, record)
    }

    LaunchedEffect(record.uri) {
        if (record.category == BackupCategory.VIDEOS && duration == null) {
            val millis = MediaThumbnails.duration(record)
            if (millis != null && millis > 0L) {
                duration = millis
                onDuration(millis)
            }
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = record.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (record.category != BackupCategory.VIDEOS) {
            // No decoder could open it, or it lives only in Telegram. The extension is more
            // useful here than a generic placeholder glyph. Videos get the play badge instead.
            Text(
                extensionLabel(record.displayName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (record.category == BackupCategory.VIDEOS) {
            // The scrim is what makes a white glyph readable over both a bright frame and the
            // empty surfaceVariant cell behind a video that would not decode.
            Surface(
                color = Color.Black.copy(alpha = 0.35f),
                shape = CircleShape,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(4.dp).size(24.dp)
                )
            }
        }

        duration?.takeIf { it > 0L }?.let { millis ->
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
            ) {
                Text(
                    Format.duration(millis),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        // A single dot rather than a word: at this size anything longer is unreadable, and the
        // preview dialog spells the status out.
        if (record.status != UploadStatus.UPLOADED) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
            )
        }
    }
}

/**
 * Tap-to-preview. [onShare] is called with true to hand the file to a viewer app and false to open
 * the system share sheet; both need real bytes on the phone, so both are hidden for a file that
 * only exists in Telegram.
 */
@Composable
private fun MediaPreviewDialog(
    record: FileRecord,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
    onShare: (openInstead: Boolean) -> Unit
) {
    val context = LocalContext.current
    val fmt = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }
    var preview by remember(record.uri) { mutableStateOf(MediaThumbnails.peek(record)) }

    LaunchedEffect(record.uri) {
        MediaThumbnails.loadPreview(context, record)?.let { preview = it }
    }

    val hasLocalBytes = record.hasLocalBytes()
    val canRestore = record.status == UploadStatus.UPLOADED && record.telegramMessageId != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(record.displayName, maxLines = 2, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 320.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val image = preview
                    if (image != null) {
                        Image(
                            bitmap = image.asImageBitmap(),
                            contentDescription = record.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            "No preview available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "${categoryLabel(record.category)} • ${Format.bytes(record.sizeBytes)}" +
                        (record.durationMillis?.takeIf { it > 0L }
                            ?.let { " • ${Format.duration(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    fmt.format(Date(record.modifiedAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    galleryStatusLine(record),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (record.status == UploadStatus.UPLOADED) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasLocalBytes) {
                        TextButton(onClick = { onShare(true) }, enabled = !busy) { Text("Open") }
                        TextButton(onClick = { onShare(false) }, enabled = !busy) { Text("Share") }
                    }
                    if (canRestore) {
                        TextButton(onClick = onRestore, enabled = !busy) {
                            Text(if (hasLocalBytes) "Restore a copy" else "Restore")
                        }
                    }
                }
                if (!hasLocalBytes && !canRestore) {
                    Text(
                        "This file is not on the phone and has no Telegram copy yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/**
 * Newest-first files split into month buckets. Deliberately done here and not in SQL: SQLite's
 * strftime works in UTC, so a photo taken at 23:30 on the 31st would land in the following month
 * for every timezone east of London.
 */
private fun groupByMonth(files: List<FileRecord>): List<GalleryEntry> {
    if (files.isEmpty()) return emptyList()
    val monthLabel = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    val calendar = Calendar.getInstance()
    val buckets = LinkedHashMap<Int, Pair<String, MutableList<FileRecord>>>()

    for (record in files) {
        calendar.timeInMillis = record.modifiedAtMillis
        val key = calendar.get(Calendar.YEAR) * 100 + calendar.get(Calendar.MONTH)
        val bucket = buckets.getOrPut(key) {
            monthLabel.format(calendar.time) to mutableListOf()
        }
        bucket.second.add(record)
    }

    val entries = ArrayList<GalleryEntry>(files.size + buckets.size)
    for (bucket in buckets.values) {
        entries.add(GalleryEntry.MonthHeader(bucket.first, bucket.second.size))
        bucket.second.forEach { entries.add(GalleryEntry.Media(it)) }
    }
    return entries
}

/** "MP4", or a plain "FILE" when the name carries nothing that looks like an extension. */
private fun extensionLabel(displayName: String): String {
    val ext = displayName.substringAfterLast('.', "").uppercase(Locale.US)
    return if (ext.isNotEmpty() && ext.length <= 5) ext else "FILE"
}

/**
 * Whether there is something on this phone to open or share. Rows rebuilt from the Telegram
 * manifest carry a "restored://" URI and no bytes at all.
 */
private fun FileRecord.hasLocalBytes(): Boolean =
    localState != LocalState.MISSING &&
        localState != LocalState.FREED &&
        (uri.startsWith("file://") || uri.startsWith("content://"))

/** The URI to read local bytes from; only meaningful when [hasLocalBytes] is true. */
private fun FileRecord.localUri(): Uri = Uri.parse(uri)

private fun galleryStatusLine(record: FileRecord): String = when (record.status) {
    UploadStatus.UPLOADED ->
        if (record.revision > 1) "Backed up (v${record.revision})" else "Backed up"
    UploadStatus.PENDING -> "Waiting for the next backup"
    UploadStatus.UPLOADING -> "Uploading now"
    UploadStatus.FAILED -> "Upload failed — ${record.lastError?.take(80) ?: "no reason recorded"}"
    UploadStatus.SKIPPED -> "Skipped"
    UploadStatus.CANCELLED -> "Cancelled"
}

/** Enough rows for one category's worth of files without holding the whole table in memory. */
private const val CATEGORY_DETAIL_LIMIT = 600

/**
 * One category, drilled into from the dashboard or Categories & Statistics — the "tap a category
 * to see its actual files, not just a count" request. Photos and videos get the same real-preview
 * grid as [GalleryScreen] (they share its month grouping and preview dialog); every other category
 * gets a plain list, since [MediaThumbnails] already returns null for anything that isn't an image
 * or video and [MediaPreviewDialog] already renders "No preview available" for that case — nothing
 * new was needed there, just a list row to tap in the first place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDetailScreen(nav: NavHostController, category: BackupCategory) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()

    // "" means All; the other two match FileRecordDao's searchFlow statusName filter exactly.
    var statusFilter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<FileRecord?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val files by remember(statusFilter) {
        dao.searchFlow(
            query = "", categoryName = category.name, statusName = statusFilter,
            localStateName = "", folder = "", chatId = 0,
            minBytes = 0, maxBytes = 0, fromMillis = 0, toMillis = 0,
            sort = 0, limit = CATEGORY_DETAIL_LIMIT
        )
    }.collectAsState(initial = emptyList())
    val total by remember(statusFilter) {
        dao.searchCountFlow(
            query = "", categoryName = category.name, statusName = statusFilter,
            localStateName = "", folder = "", chatId = 0,
            minBytes = 0, maxBytes = 0, fromMillis = 0, toMillis = 0
        )
    }.collectAsState(initial = 0)
    val restore by repository.restoreState.collectAsState()

    val showsMedia = category == BackupCategory.PHOTOS || category == BackupCategory.VIDEOS
    val entries = remember(files, showsMedia) { if (showsMedia) groupByMonth(files) else emptyList() }

    DisposableEffect(Unit) { onDispose { MediaThumbnails.trim() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(categoryLabel(category)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = statusFilter == "", onClick = { statusFilter = "" }, label = { Text("All") })
                FilterChip(
                    selected = statusFilter == "PENDING",
                    onClick = { statusFilter = "PENDING" },
                    label = { Text("Pending") }
                )
                FilterChip(
                    selected = statusFilter == "UPLOADED",
                    onClick = { statusFilter = "UPLOADED" },
                    label = { Text("Uploaded") }
                )
            }

            Text(
                if (files.size >= CATEGORY_DETAIL_LIMIT) {
                    "Newest ${Format.count(files.size)} of ${Format.count(total)}"
                } else {
                    "${Format.count(files.size)} of ${Format.count(total)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            restore?.let { state ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    when {
                        state.error != null -> Text(
                            "Restore failed: ${state.error}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        state.finishedPath != null -> Text(
                            "Saved to ${state.finishedPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        else -> {
                            Text("Restoring ${state.fileName}…", style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(
                                progress = { state.fraction },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            when {
                files.isEmpty() -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            when (statusFilter) {
                                "PENDING" -> "Nothing pending in ${categoryLabel(category)}."
                                "UPLOADED" -> "Nothing uploaded yet in ${categoryLabel(category)}."
                                else -> "No files found in ${categoryLabel(category)} yet."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(32.dp)
                        )
                    }
                }
                showsMedia -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = entries,
                            key = { entry ->
                                when (entry) {
                                    is GalleryEntry.MonthHeader -> "header:${entry.label}"
                                    is GalleryEntry.Media -> "media:${entry.record.id}"
                                }
                            },
                            span = { entry ->
                                if (entry is GalleryEntry.MonthHeader) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                            }
                        ) { entry ->
                            when (entry) {
                                is GalleryEntry.MonthHeader -> MonthHeaderRow(entry)
                                is GalleryEntry.Media -> MediaCell(
                                    record = entry.record,
                                    onClick = { selected = entry.record; message = null },
                                    onDuration = { ms ->
                                        scope.launch { runCatching { dao.setDuration(entry.record.id, ms) } }
                                    }
                                )
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(files, key = { it.id }) { record ->
                            FileListRow(record = record, onClick = { selected = record; message = null })
                            Divider()
                        }
                        if (files.size >= CATEGORY_DETAIL_LIMIT) {
                            item {
                                Text(
                                    "Showing the newest $CATEGORY_DETAIL_LIMIT — use a status filter to narrow it down.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { record ->
        MediaPreviewDialog(
            record = record,
            busy = busy,
            onDismiss = { selected = null },
            onRestore = {
                busy = true
                message = null
                scope.launch {
                    runCatching { repository.restoreFile(record) }
                        .onSuccess { message = "Restored ${record.displayName} to ${it.parent}" }
                        .onFailure { message = "Could not restore it: ${it.message?.take(160)}" }
                    busy = false
                    selected = null
                }
            },
            onShare = { openInstead ->
                busy = true
                message = null
                scope.launch {
                    val staged = Sharing.stage(context, record.localUri(), record.displayName)
                    if (staged == null) {
                        message = "That file is not on this phone any more — restore it first."
                    } else {
                        val handled = if (openInstead) {
                            Sharing.open(context, staged, record.displayName)
                        } else {
                            Sharing.share(context, staged, record.displayName)
                        }
                        if (!handled) message = "No app on this phone can handle that file."
                    }
                    busy = false
                }
            }
        )
    }
}

/**
 * One row for a category with no visual preview (PDFs, documents, audio, call recordings, other
 * files) — the extension badge plus name, size, and date, tap to open the same detail dialog the
 * gallery grid uses.
 */
@Composable
private fun FileListRow(record: FileRecord, onClick: () -> Unit) {
    val fmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                extensionLabel(record.displayName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(record.displayName, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${Format.bytes(record.sizeBytes)} • ${fmt.format(Date(record.modifiedAtMillis))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (record.status != UploadStatus.UPLOADED) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (record.status == UploadStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
            )
        }
    }
}

