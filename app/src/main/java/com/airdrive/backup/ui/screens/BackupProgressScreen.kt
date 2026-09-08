package com.airdrive.backup.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.*
import com.airdrive.backup.data.prefs.NetworkPolicy
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.repo.BackupPhase
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.Format
import com.airdrive.backup.util.MediaThumbnails
import com.airdrive.backup.work.WorkScheduler
import kotlinx.coroutines.launch

private val Blue = Color(0xFF2F6FEA)
private val BlueDark = Color(0xFF183B7A)
private val BlueSoft = Color(0xFFEAF2FF)
private val Green = Color(0xFF20A463)
private val GreenSoft = Color(0xFFE6F7EE)
private val Orange = Color(0xFFF59E0B)
private val OrangeSoft = Color(0xFFFFF4DD)
private val Red = Color(0xFFE5484D)
private val RedSoft = Color(0xFFFFE8E8)
private val Purple = Color(0xFF7C3AED)
private val PurpleSoft = Color(0xFFF0E8FF)
private val Cyan = Color(0xFF18B8C8)
private val CyanSoft = Color(0xFFE2F8FA)
private val Pink = Color(0xFFE83E73)
private val PinkSoft = Color(0xFFFFE9F0)
private val Surface = Color(0xFFF7F9FD)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupProgressScreen(nav: NavHostController) {
    val context = LocalContext.current
    val repository = remember { BackupRepository.get(context) }
    val settings = remember { SettingsStore(context) }
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val progress by repository.progress.collectAsState()
    val paused by repository.paused.collectAsState()
    val pending by dao.pendingCountFlow().collectAsState(initial = 0)
    val failed by dao.failedCountFlow().collectAsState(initial = 0)
    val uploaded by dao.uploadedCountFlow().collectAsState(initial = 0)
    val uploadedBytes by dao.uploadedBytesFlow().collectAsState(initial = 0L)
    val totals by dao.categoryTotalsFlow().collectAsState(initial = emptyList())
    val recent by dao.recentActivityFlow(4).collectAsState(initial = emptyList())
    val policy by settings.networkPolicy.collectAsState(initial = NetworkPolicy.WIFI_ONLY)
    val chargingOnly by settings.chargingOnly.collectAsState(initial = false)
    val current = remember(recent, progress.currentFileId) { recent.firstOrNull { it.id == progress.currentFileId } }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(current?.uri, preview) {
        bitmap = if (preview && current != null) MediaThumbnails.loadPreview(context, current) else null
    }

    val scanning = progress.phase == BackupPhase.SCANNING
    val status = when {
        scanning -> "Scanning storage"
        progress.isRunning && paused -> "Paused"
        progress.isRunning -> "Uploading to Telegram"
        progress.phase == BackupPhase.FINISHED -> "Backup finished"
        else -> "Ready to back up"
    }

    Scaffold(
        containerColor = Surface,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Blue), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Cloud, null, tint = Color.White, modifier = Modifier.size(27.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("AirDrive Backup", fontWeight = FontWeight.Bold)
                            Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
                actions = {
                    Surface(shape = RoundedCornerShape(18.dp), color = GreenSoft) {
                        Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Green))
                            Spacer(Modifier.width(5.dp))
                            Text("Connected", style = MaterialTheme.typography.labelSmall, color = Color(0xFF126A43), fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(onClick = { nav.navigate(Routes.BACKUP_SETTINGS) }) { Icon(Icons.Default.Settings, "Backup settings") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            MenuItem("Backup settings") { nav.navigate(Routes.BACKUP_SETTINGS) }
                            MenuItem("Telegram destination") { nav.navigate(Routes.DESTINATION) }
                            MenuItem("Backup timeline") { nav.navigate(Routes.TIMELINE) }
                            MenuItem("Failed uploads ($failed)") { nav.navigate(Routes.FAILED_UPLOADS) }
                            MenuItem("All activity") { nav.navigate(Routes.ACTIVITY_HISTORY) }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BackupHero(progress, paused, scanning, { repository.setPaused(!paused) }, { progress.currentFileId?.let { id -> scope.launch { repository.cancelUpload(id) } } }, {
                WorkScheduler.pauseManual(context)
                repository.setPaused(false)
                nav.navigate(Routes.DASHBOARD) { popUpTo(Routes.DASHBOARD) { inclusive = true } }
            }, { nav.navigate(Routes.TIMELINE) })

            CurrentFileCard(current, progress, { if (current != null) preview = true else nav.navigate(Routes.ACTIVITY_HISTORY) }, { nav.navigate(Routes.ACTIVITY_HISTORY) })

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(Format.count(uploaded), "Backed up", Icons.Default.Description, Green, GreenSoft, Modifier.weight(1f))
                StatCard(Format.count(pending), "Pending", Icons.Default.Schedule, Orange, OrangeSoft, Modifier.weight(1f))
                StatCard(Format.count(failed), "Failed", Icons.Default.Warning, Red, RedSoft, Modifier.weight(1f))
                StatCard(Format.bytes(uploadedBytes), "Total", Icons.Default.Storage, Purple, PurpleSoft, Modifier.weight(1f))
            }

            SectionTitle("Categories") { nav.navigate(Routes.CATEGORIES_STATS) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryCard(BackupCategory.PHOTOS, "Photos", Icons.Default.Image, Blue, BlueSoft, totals, nav)
                CategoryCard(BackupCategory.VIDEOS, "Videos", Icons.Default.VideoLibrary, Orange, OrangeSoft, totals, nav)
                CategoryCard(BackupCategory.PDFS, "PDFs", Icons.Default.Description, Cyan, CyanSoft, totals, nav)
                CategoryCard(BackupCategory.AUDIO, "Audio", Icons.Default.AudioFile, Purple, PurpleSoft, totals, nav)
                CategoryCard(BackupCategory.WORD_EXCEL, "Documents", Icons.Default.InsertDriveFile, Pink, PinkSoft, totals, nav)
                CategoryCard(BackupCategory.CALL_RECORDINGS, "Calls", Icons.Default.Call, Red, RedSoft, totals, nav)
                CategoryCard(BackupCategory.OTHER_FILES, "Other", Icons.Default.MoreHoriz, Color(0xFF64748B), Color(0xFFE9EEF5), totals, nav)
            }

            DetailCard(Icons.Default.Send, Blue, "Telegram destination", "Saved Messages • ${Format.bytes(uploadedBytes)} used", "Change") { nav.navigate(Routes.DESTINATION) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { DetailCard(Icons.Default.Settings, Blue, "Backup settings", "Folders, file types, network", "") { nav.navigate(Routes.BACKUP_SETTINGS) } }
                Box(Modifier.weight(1f)) { DetailCard(Icons.Default.Schedule, Blue, "Schedule backup", "Automatic backup rules", "") { nav.navigate(Routes.BACKUP_SETTINGS) } }
            }

            SectionTitle("Recent activity") { nav.navigate(Routes.ACTIVITY_HISTORY) }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    recent.forEachIndexed { index, record -> RecentRow(record, index == recent.lastIndex) { nav.navigate(Routes.ACTIVITY_HISTORY) } }
                    if (recent.isEmpty()) Text("No activity yet", Modifier.padding(vertical = 18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            DetailCard(Icons.Default.Security, Blue, "Your data is safe with Telegram", "Private, encrypted and always accessible.", "Learn more") { nav.navigate(Routes.TELEGRAM_SETTINGS) }
            if (!progress.isRunning && pending > 0) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = OrangeSoft)) {
                    Text("$pending file(s) are queued. Automatic runs wait for ${networkText(policy, chargingOnly)}.", Modifier.padding(15.dp), color = Color(0xFF7A4A00))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (preview) {
        AlertDialog(
            onDismissRequest = { preview = false },
            title = { Text(current?.displayName ?: progress.currentFileName ?: "Current file", maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (bitmap != null) Image(bitmap!!.asImageBitmap(), "File preview", Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(16.dp)), ContentScale.Fit)
                    else Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)).background(BlueSoft), contentAlignment = Alignment.Center) { Icon(Icons.Default.InsertDriveFile, null, tint = Blue, modifier = Modifier.size(60.dp)) }
                    Text(current?.let { "${backupCategoryName(it.category)} • ${Format.bytes(it.sizeBytes)}" } ?: "Waiting for file metadata")
                    Text("Uploaded ${Format.bytes(progress.currentFileUploadedBytes)} of ${Format.bytes(progress.currentFileBytes)}", fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = { TextButton(onClick = { preview = false; nav.navigate(Routes.ACTIVITY_HISTORY) }) { Text("Open activity") } },
            dismissButton = { TextButton(onClick = { preview = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun BackupHero(progress: com.airdrive.backup.data.repo.UploadProgress, paused: Boolean, scanning: Boolean, onPause: () -> Unit, onCancel: () -> Unit, onStop: () -> Unit, onDetails: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = BlueSoft)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { if (scanning) 0.18f else progress.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize(), strokeWidth = 10.dp, trackColor = Color(0xFFD4E3F8), color = Blue)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${progress.percent}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = BlueDark)
                        Text("Backing up", style = MaterialTheme.typography.labelMedium, color = BlueDark)
                        Text("${progress.doneFiles} / ${progress.totalFiles}", style = MaterialTheme.typography.labelSmall, color = BlueDark)
                    }
                }
                Spacer(Modifier.width(15.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (paused) "Backup paused" else if (scanning) "Scanning your files" else "Backup in progress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = BlueDark)
                    Spacer(Modifier.height(4.dp))
                    Text("Your files are being securely uploaded to your Telegram account.", color = Color(0xFF5C7094), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Metric("↑", Format.bytes(progress.effectiveBytes), "of ${Format.bytes(progress.totalBytesQueued)}", Modifier.weight(1f))
                        Metric("ϟ", if (progress.bytesPerSecond > 0) "${Format.bytes(progress.bytesPerSecond)}/s" else "—", "Speed", Modifier.weight(1f))
                        Metric("◷", eta(progress.etaSeconds), "ETA", Modifier.weight(1f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPause, modifier = Modifier.weight(1.3f).height(50.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                    Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null); Spacer(Modifier.width(6.dp)); Text(if (paused) "Resume" else "Pause", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onCancel, enabled = progress.currentFileId != null, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Red)) {
                    Icon(Icons.Default.Close, null); Spacer(Modifier.width(4.dp)); Text("Cancel")
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(.8f).height(50.dp), shape = RoundedCornerShape(17.dp)) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(4.dp)); Text("Stop") }
            }
            OutlinedButton(onClick = onDetails, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(15.dp)) {
                Icon(Icons.Default.List, null); Spacer(Modifier.width(7.dp)); Text("View full backup details"); Spacer(Modifier.weight(1f)); Icon(Icons.Default.ArrowForward, null)
            }
        }
    }
}

@Composable
private fun Metric(icon: String, value: String, label: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = .82f)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(icon, color = Blue, fontWeight = FontWeight.Bold)
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CurrentFileCard(record: FileRecord?, progress: com.airdrive.backup.data.repo.UploadProgress, onOpen: () -> Unit, onAll: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Current file", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = BlueDark, modifier = Modifier.weight(1f))
                TextButton(onClick = onAll) { Text("View all") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileThumb(record, Modifier.size(76.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(progress.currentFileName ?: record?.displayName ?: "Preparing next file…", maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(record?.let { "${backupCategoryName(it.category)} • ${Format.bytes(it.sizeBytes)}" } ?: "Waiting for the next file", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { if (progress.currentFileBytes > 0) (progress.currentFileUploadedBytes.toFloat() / progress.currentFileBytes).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth(), color = Blue, trackColor = BlueSoft)
                    Text("${Format.bytes(progress.currentFileUploadedBytes)} / ${Format.bytes(progress.currentFileBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(Icons.Default.InsertDriveFile, backupExtension(record?.displayName ?: progress.currentFileName.orEmpty()), Modifier.weight(1f))
                Pill(Icons.Default.Folder, record?.let { folderName(it.uri) } ?: "Local storage", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FileThumb(record: FileRecord?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(BlueSoft), contentAlignment = Alignment.Center) {
        val image = remember(record?.uri) { record?.let(MediaThumbnails::peek) }
        if (image != null) Image(image.asImageBitmap(), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.InsertDriveFile, null, tint = Blue, modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun StatCard(value: String, label: String, icon: ImageVector, tint: Color, soft: Color, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(soft), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SectionTitle(title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text("See all", color = Blue) }
    }
}

@Composable
private fun CategoryCard(category: BackupCategory, label: String, icon: ImageVector, tint: Color, soft: Color, totals: List<CategoryTotals>, nav: NavHostController) {
    val total = totals.firstOrNull { it.category == category }
    val count = total?.total ?: 0
    val uploaded = total?.uploaded ?: 0
    Card(Modifier.width(126.dp).clickable { nav.navigate("${Routes.CATEGORY_DETAIL}/${category.name}") }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(soft), contentAlignment = Alignment.Center) { Icon(icon, label, tint = tint, modifier = Modifier.size(23.dp)) }
            Spacer(Modifier.height(7.dp))
            Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${Format.count(count)} files", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            LinearProgressIndicator(progress = { if (count > 0) (uploaded.toFloat() / count).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp), color = tint, trackColor = Color(0xFFE6EBF3))
        }
    }
}

@Composable
private fun DetailCard(icon: ImageVector, tint: Color, title: String, subtitle: String, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(21.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(BlueSoft), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (action.isNotBlank()) Text(action, color = Blue, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Default.ChevronRight, null, tint = Blue)
        }
    }
}

@Composable
private fun RecentRow(record: FileRecord, last: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            FileThumb(record, Modifier.size(42.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(record.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text("${backupStatus(record.status)} • ${Format.bytes(record.sizeBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Blue)
        }
        if (!last) HorizontalDivider(color = Color(0xFFE8EDF5))
    }
}

@Composable
private fun Pill(icon: ImageVector, text: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(13.dp), color = BlueSoft) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Blue, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) { DropdownMenuItem(text = { Text(label) }, onClick = onClick) }

private fun backupCategoryName(category: BackupCategory): String = when (category) {
    BackupCategory.PHOTOS -> "Photos"
    BackupCategory.VIDEOS -> "Videos"
    BackupCategory.PDFS -> "PDFs"
    BackupCategory.WORD_EXCEL -> "Documents"
    BackupCategory.AUDIO -> "Audio"
    BackupCategory.CALL_RECORDINGS -> "Calls"
    BackupCategory.OTHER_FILES -> "Other"
}

private fun backupStatus(status: UploadStatus): String = when (status) {
    UploadStatus.PENDING -> "Queued"
    UploadStatus.UPLOADING -> "Uploading"
    UploadStatus.UPLOADED -> "Backed up"
    UploadStatus.FAILED -> "Failed"
    UploadStatus.SKIPPED -> "Skipped"
    UploadStatus.CANCELLED -> "Cancelled"
}

private fun backupExtension(name: String): String = name.substringAfterLast('.', "FILE").uppercase().take(6)

private fun folderName(uri: String): String {
    val path = uri.substringAfter("file://", uri)
    val parts = path.split('/').filter { it.isNotBlank() }
    return if (parts.size >= 2) parts[parts.lastIndex - 1] else "Local storage"
}

private fun eta(seconds: Long): String {
    if (seconds <= 0) return "—"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when { h > 0 -> "${h}h ${m}m"; m > 0 -> "${m}m ${s}s"; else -> "${s}s" }
}

private fun networkText(policy: NetworkPolicy, chargingOnly: Boolean): String = when {
    chargingOnly -> "charging"
    policy == NetworkPolicy.WIFI_ONLY -> "Wi-Fi"
    policy == NetworkPolicy.NOT_ROAMING -> "Wi-Fi/mobile without roaming"
    else -> "any allowed network"
}
