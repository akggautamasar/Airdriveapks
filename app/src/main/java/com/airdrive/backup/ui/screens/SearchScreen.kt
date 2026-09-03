package com.airdrive.backup.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.LocalState
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.util.Format
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Any time", "Last 7 days"… kept as an enum so the chip row and the query cannot disagree. */
private enum class DateWindow(val label: String, val days: Long) {
    ANY("Any time", 0), WEEK("Last 7 days", 7), MONTH("Last 30 days", 30),
    QUARTER("Last 90 days", 90), YEAR("Last year", 365)
}

private enum class SizeBand(val label: String, val minBytes: Long, val maxBytes: Long) {
    ANY("Any size", 0, 0),
    SMALL("Under 1 MB", 0, 1L shl 20),
    MEDIUM("1–100 MB", 1L shl 20, 100L shl 20),
    LARGE("100 MB – 1 GB", 100L shl 20, 1L shl 30),
    HUGE("Over 1 GB", 1L shl 30, 0)
}

private enum class SortOrder(val label: String, val code: Int) {
    NEWEST("Newest", 0), OLDEST("Oldest", 4), LARGEST("Largest", 1),
    SMALLEST("Smallest", 5), NAME_AZ("Name A–Z", 2), NAME_ZA("Name Z–A", 3)
}

/**
 * Wishlist item 5: search that feels like Drive rather than a log viewer. Every filter is pushed
 * into the one SQL statement behind [com.airdrive.backup.data.db.FileRecordDao.searchFlow] — a
 * fully scanned phone is tens of thousands of rows, so filtering in Kotlin is not an option.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: NavHostController) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val repository = remember { BackupRepository.get(context) }
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<BackupCategory?>(null) }
    var status by remember { mutableStateOf<UploadStatus?>(null) }
    var localState by remember { mutableStateOf<LocalState?>(null) }
    var folder by remember { mutableStateOf("") }
    var chatId by remember { mutableStateOf(0L) }
    var dateWindow by remember { mutableStateOf(DateWindow.ANY) }
    var sizeBand by remember { mutableStateOf(SizeBand.ANY) }
    var sort by remember { mutableStateOf(SortOrder.NEWEST) }
    var filtersOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val fromMillis = remember(dateWindow) {
        if (dateWindow.days == 0L) 0L
        else System.currentTimeMillis() - dateWindow.days * 24L * 60L * 60L * 1000L
    }

    val results by remember(
        query, category, status, localState, folder, chatId, dateWindow, sizeBand, sort
    ) {
        dao.searchFlow(
            query = query.trim(),
            categoryName = category?.name.orEmpty(),
            statusName = status?.name.orEmpty(),
            localStateName = localState?.name.orEmpty(),
            folder = folder.trim(),
            chatId = chatId,
            minBytes = sizeBand.minBytes,
            maxBytes = sizeBand.maxBytes,
            fromMillis = fromMillis,
            toMillis = 0L,
            sort = sort.code,
            limit = LIMIT
        )
    }.collectAsState(initial = emptyList())

    val total by remember(query, category, status, localState, folder, chatId, dateWindow, sizeBand) {
        dao.searchCountFlow(
            query = query.trim(),
            categoryName = category?.name.orEmpty(),
            statusName = status?.name.orEmpty(),
            localStateName = localState?.name.orEmpty(),
            folder = folder.trim(),
            chatId = chatId,
            minBytes = sizeBand.minBytes,
            maxBytes = sizeBand.maxBytes,
            fromMillis = fromMillis,
            toMillis = 0L
        )
    }.collectAsState(initial = 0)

    val chatIds by remember { dao.destinationChatIdsFlow() }.collectAsState(initial = emptyList())
    val channels by settings.allChannels.collectAsState(initial = null)

    val activeFilters = listOfNotNull(
        category?.let { categoryLabel(it) },
        status?.let { it.name.lowercase(Locale.getDefault()) },
        localState?.let { localStateLabel(it) },
        folder.trim().takeIf { it.isNotBlank() },
        dateWindow.takeIf { it != DateWindow.ANY }?.label,
        sizeBand.takeIf { it != SizeBand.ANY }?.label,
        chatId.takeIf { it != 0L }?.let { destinationLabel(it, channels?.perCategory) }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search backups") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { filtersOpen = !filtersOpen }) {
                        Text(if (filtersOpen) "Hide filters" else "Filters")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("File name or path") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${Format.count(total)} match${if (total == 1) "" else "es"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (activeFilters.isNotEmpty()) {
                    TextButton(onClick = {
                        category = null; status = null; localState = null
                        folder = ""; chatId = 0L
                        dateWindow = DateWindow.ANY; sizeBand = SizeBand.ANY
                    }) { Text("Clear") }
                }
            }

            if (activeFilters.isNotEmpty() && !filtersOpen) {
                Text(
                    activeFilters.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            if (filtersOpen) {
                FilterPanel(
                    category = category, onCategory = { category = it },
                    status = status, onStatus = { status = it },
                    localState = localState, onLocalState = { localState = it },
                    dateWindow = dateWindow, onDateWindow = { dateWindow = it },
                    sizeBand = sizeBand, onSizeBand = { sizeBand = it },
                    sort = sort, onSort = { sort = it },
                    folder = folder, onFolder = { folder = it },
                    chatId = chatId, onChatId = { chatId = it },
                    chatIds = chatIds, channelMap = channels?.perCategory
                )
            }

            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Divider(modifier = Modifier.padding(top = 8.dp))

            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (results.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Nothing matches. Try fewer filters, or part of the file name.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                items(results, key = { it.id }) { record: FileRecord ->
                    SearchResultRow(record) {
                        message = null
                        scope.launch {
                            runCatching { repository.restoreFile(record) }
                                .onSuccess { message = "Saved to ${it.absolutePath}" }
                                .onFailure { message = it.message?.take(200) }
                        }
                    }
                    Divider()
                }
                if (total > results.size) {
                    item {
                        Text(
                            "Showing the first ${Format.count(results.size)} of " +
                                "${Format.count(total)} — narrow the search to see the rest.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

private const val LIMIT = 200

@Composable
private fun FilterPanel(
    category: BackupCategory?, onCategory: (BackupCategory?) -> Unit,
    status: UploadStatus?, onStatus: (UploadStatus?) -> Unit,
    localState: LocalState?, onLocalState: (LocalState?) -> Unit,
    dateWindow: DateWindow, onDateWindow: (DateWindow) -> Unit,
    sizeBand: SizeBand, onSizeBand: (SizeBand) -> Unit,
    sort: SortOrder, onSort: (SortOrder) -> Unit,
    folder: String, onFolder: (String) -> Unit,
    chatId: Long, onChatId: (Long) -> Unit,
    chatIds: List<Long>, channelMap: Map<BackupCategory, Long>?
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        FilterRow("File type") {
            ChoiceChip("All", category == null) { onCategory(null) }
            for (c in BackupCategory.values()) {
                ChoiceChip(categoryLabel(c), category == c) {
                    onCategory(if (category == c) null else c)
                }
            }
        }
        FilterRow("Backup status") {
            ChoiceChip("Any", status == null) { onStatus(null) }
            for (s in UploadStatus.values()) {
                ChoiceChip(s.name.lowercase(Locale.getDefault()).replaceFirstChar { it.uppercase() }, status == s) {
                    onStatus(if (status == s) null else s)
                }
            }
        }
        FilterRow("On this phone") {
            ChoiceChip("Any", localState == null) { onLocalState(null) }
            for (l in LocalState.values()) {
                ChoiceChip(localStateLabel(l), localState == l) {
                    onLocalState(if (localState == l) null else l)
                }
            }
        }
        FilterRow("Date") {
            for (w in DateWindow.values()) {
                ChoiceChip(w.label, dateWindow == w) { onDateWindow(w) }
            }
        }
        FilterRow("Size") {
            for (b in SizeBand.values()) {
                ChoiceChip(b.label, sizeBand == b) { onSizeBand(b) }
            }
        }
        FilterRow("Sort") {
            for (o in SortOrder.values()) {
                ChoiceChip(o.label, sort == o) { onSort(o) }
            }
        }
        if (chatIds.isNotEmpty()) {
            FilterRow("Telegram destination") {
                ChoiceChip("Any", chatId == 0L) { onChatId(0L) }
                for (id in chatIds) {
                    ChoiceChip(destinationLabel(id, channelMap), chatId == id) {
                        onChatId(if (chatId == id) 0L else id)
                    }
                }
            }
        }
        OutlinedTextField(
            value = folder,
            onValueChange = onFolder,
            label = { Text("Folder contains") },
            placeholder = { Text("DCIM/Camera") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun FilterRow(label: String, content: @Composable RowScope.() -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp)
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.padding(end = 8.dp)
    )
}

@Composable
private fun SearchResultRow(record: FileRecord, onRestore: () -> Unit) {
    val fmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(record.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                "${categoryLabel(record.category)} • ${Format.bytes(record.sizeBytes)} • " +
                    fmt.format(Date(record.modifiedAtMillis)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                searchStatusLine(record),
                style = MaterialTheme.typography.bodySmall,
                color = if (record.status == UploadStatus.UPLOADED) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (record.status == UploadStatus.UPLOADED && record.telegramMessageId != null) {
            TextButton(onClick = onRestore) { Text("Restore") }
        }
    }
}

/** One line that answers "where is this file right now?" — the whole point of the screen. */
private fun searchStatusLine(record: FileRecord): String {
    val backup = when (record.status) {
        UploadStatus.UPLOADED -> if (record.revision > 1) "Backed up (v${record.revision})" else "Backed up"
        UploadStatus.PENDING -> "Waiting to upload"
        UploadStatus.UPLOADING -> "Uploading now"
        UploadStatus.FAILED -> "Upload failed"
        UploadStatus.SKIPPED -> "Skipped by your rules"
        UploadStatus.CANCELLED -> "Cancelled"
    }
    val local = when (record.localState) {
        LocalState.PRESENT -> null
        LocalState.MISSING -> "not on this phone"
        LocalState.FREED -> "local copy freed"
        LocalState.UNKNOWN -> "local copy unknown"
    }
    return if (local == null) backup else "$backup • $local"
}

private fun localStateLabel(state: LocalState): String = when (state) {
    LocalState.PRESENT -> "On the phone"
    LocalState.MISSING -> "Deleted locally"
    LocalState.FREED -> "Freed by cleanup"
    LocalState.UNKNOWN -> "Unknown"
}

/**
 * A chat id on its own means nothing to a person, so it is named after the category that points
 * at it. Falls back to the last digits, which is still enough to tell two channels apart.
 */
private fun destinationLabel(id: Long, channels: Map<BackupCategory, Long>?): String {
    val owners = channels?.filterValues { it == id }?.keys.orEmpty()
    return when {
        owners.size == 1 -> categoryLabel(owners.first())
        owners.size > 1 -> "${owners.size} categories"
        else -> "Chat …${id.toString().takeLast(4)}"
    }
}
