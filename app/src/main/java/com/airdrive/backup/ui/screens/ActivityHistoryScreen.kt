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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
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
import com.airdrive.backup.data.db.*
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.util.MediaThumbnails
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val ABlue = Color(0xFF2F6FEA)
private val ABlueLight = Color(0xFFDCEBFF)
private val APurple = Color(0xFF7C3AED)
private val APurpleLight = Color(0xFFF0DFFF)
private val AGreen = Color(0xFF20A463)
private val AGreenLight = Color(0xFFDDF6EC)
private val AOrange = Color(0xFFF59E0B)
private val AOrangeLight = Color(0xFFFFEBD0)
private val ARed = Color(0xFFE84A5F)
private val ABg = Color(0xFFF7F9FD)
private val AText = Color(0xFF17213B)
private val ASub = Color(0xFF6E7788)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityHistoryScreen(nav: NavHostController) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<UploadStatus?>(null) }
    var categoryFilter by remember { mutableStateOf<BackupCategory?>(null) }
    val activity by remember(query, filter, categoryFilter) {
        val q = query.trim(); val category = categoryFilter?.name ?: ""
        if (filter == null) dao.activityFlow(q, category, 500) else dao.activityByStatusFlow(filter!!, q, category, 500)
    }.collectAsState(initial = emptyList())
    DisposableEffect(Unit) { onDispose { MediaThumbnails.trim() } }

    Scaffold(containerColor = ABg, topBar = {
        TopAppBar(
            title = { Column { Text("Activity", fontWeight = FontWeight.Bold, color = AText); Text("Everything AirDrive has backed up", style = MaterialTheme.typography.bodySmall, color = ASub) } },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ABg)
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(modifier = Modifier.size(46.dp), shape = RoundedCornerShape(14.dp), color = ABlueLight) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.History, null, tint = ABlue) } }
                    Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Backup activity", fontWeight = FontWeight.Bold, color = AText); Text("${activity.size} recent records shown", style = MaterialTheme.typography.bodySmall, color = ASub) }
                    Surface(shape = RoundedCornerShape(10.dp), color = AGreenLight) { Text("Live", Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = AGreen, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = query, onValueChange = { query = it }, leadingIcon = { Icon(Icons.Default.Search, null, tint = Color(0xFF596579)) }, placeholder = { Text("Search by file name") }, singleLine = true, shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White, unfocusedBorderColor = Color(0xFFD9DEEA), focusedBorderColor = ABlue), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(58.dp))
            Spacer(Modifier.height(9.dp))
            FilterRow { ActivityChip("All categories", categoryFilter == null, ABlue, ABlueLight) { categoryFilter = null }; BackupCategory.values().forEach { category -> ActivityChip(categoryLabel(category), categoryFilter == category, categoryTint(category), categoryTint(category).copy(alpha = .12f)) { categoryFilter = if (categoryFilter == category) null else category } } }
            Spacer(Modifier.height(7.dp))
            FilterRow { ActivityChip("All", filter == null, ABlue, ABlueLight) { filter = null }; UploadStatus.values().forEach { status -> ActivityChip(statusLabel(status), filter == status, statusTint(status), statusTint(status).copy(alpha = .12f)) { filter = if (filter == status) null else status } } }
            Spacer(Modifier.height(8.dp))
            if (activity.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.History, null, Modifier.size(48.dp), tint = ABlue.copy(alpha = .55f)); Spacer(Modifier.height(8.dp)); Text("Nothing matches those filters", color = ASub) } }
            else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(activity, key = { it.id }) { record -> ActivityCard(record, repository, scope) } }
        }
    }
}

@Composable private fun FilterRow(content: @Composable RowScope.() -> Unit) { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content) }
@Composable private fun ActivityChip(label: String, selected: Boolean, tint: Color, bg: Color, onClick: () -> Unit) { FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) }, shape = RoundedCornerShape(13.dp), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = bg, selectedLabelColor = tint, containerColor = Color.White, labelColor = ASub), border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = Color(0xFFD9DEEA), selectedBorderColor = tint)) }

@Composable private fun ActivityCard(record: FileRecord, repository: BackupRepository, scope: kotlinx.coroutines.CoroutineScope) {
    val context = LocalContext.current; val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }; val showsThumbnail = record.category == BackupCategory.PHOTOS || record.category == BackupCategory.VIDEOS
    var bitmap by remember(record.uri) { mutableStateOf(if (showsThumbnail) MediaThumbnails.peek(record) else null) }
    LaunchedEffect(record.uri, showsThumbnail) { if (showsThumbnail && bitmap == null) bitmap = MediaThumbnails.load(context, record) }
    val tint = statusTint(record.status)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFFF0F3F8)), contentAlignment = Alignment.Center) { bitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = record.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } ?: Text(record.displayName.substringAfterLast('.', "FILE").uppercase(Locale.getDefault()).take(5), style = MaterialTheme.typography.labelSmall, color = ASub) }
            Spacer(Modifier.width(11.dp)); Column(Modifier.weight(1f)) {
                Text(record.displayName, fontWeight = FontWeight.SemiBold, color = AText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${com.airdrive.backup.ui.screens.formatBytes(record.sizeBytes)} • ${fmt.format(Date(record.uploadedAtMillis ?: record.addedAtMillis))}", style = MaterialTheme.typography.bodySmall, color = ASub)
                Spacer(Modifier.height(4.dp)); Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Surface(shape = RoundedCornerShape(8.dp), color = tint.copy(alpha = .11f)) { Text(categoryLabel(record.category), Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = categoryTint(record.category), fontWeight = FontWeight.SemiBold) }
                    Surface(shape = RoundedCornerShape(8.dp), color = tint.copy(alpha = .11f)) { Text(statusLabel(record.status), Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.SemiBold) }
                }
                record.lastError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = ARed, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)) }
            }
            when (record.status) { UploadStatus.PENDING, UploadStatus.UPLOADING -> TextButton(onClick = { scope.launch { repository.cancelUpload(record.id) } }) { Text("Cancel", color = ABlue) }; UploadStatus.CANCELLED -> TextButton(onClick = { scope.launch { repository.requeueCancelled(record.id) } }) { Text("Retry", color = ABlue) }; else -> Icon(Icons.Default.Upload, null, tint = tint.copy(alpha = .7f), modifier = Modifier.size(20.dp)) }
        }
    }
}

private fun statusLabel(status: UploadStatus): String = when (status) { UploadStatus.UPLOADED -> "Uploaded"; UploadStatus.FAILED -> "Failed"; UploadStatus.UPLOADING -> "Uploading"; UploadStatus.PENDING -> "Pending"; UploadStatus.SKIPPED -> "Skipped"; UploadStatus.CANCELLED -> "Cancelled" }
private fun statusTint(status: UploadStatus): Color = when (status) { UploadStatus.UPLOADED -> AGreen; UploadStatus.FAILED -> ARed; UploadStatus.UPLOADING -> ABlue; UploadStatus.PENDING -> AOrange; UploadStatus.SKIPPED -> ASub; UploadStatus.CANCELLED -> APurple }
private fun categoryTint(category: BackupCategory): Color = when (category) { BackupCategory.PHOTOS -> AGreen; BackupCategory.VIDEOS -> ARed; BackupCategory.PDFS -> AOrange; BackupCategory.WORD_EXCEL -> APurple; BackupCategory.AUDIO -> Color(0xFF18B8C8); BackupCategory.CALL_RECORDINGS -> ABlue; BackupCategory.OTHER_FILES -> Color(0xFF18B8C8) }
