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

private val BackupBlue = Color(0xFF2F6FEA)
private val BackupBlueDark = Color(0xFF183B7A)
private val BackupBlueSoft = Color(0xFFEAF2FF)
private val BackupGreen = Color(0xFF20A463)
private val BackupGreenSoft = Color(0xFFE6F7EE)
private val BackupOrange = Color(0xFFF59E0B)
private val BackupOrangeSoft = Color(0xFFFFF4DD)
private val BackupRed = Color(0xFFE5484D)
private val BackupRedSoft = Color(0xFFFFE8E8)
private val BackupPurple = Color(0xFF7C3AED)
private val BackupPurpleSoft = Color(0xFFF0E8FF)
private val BackupCyan = Color(0xFF18B8C8)
private val BackupCyanSoft = Color(0xFFE2F8FA)
private val BackupPink = Color(0xFFE83E73)
private val BackupPinkSoft = Color(0xFFFFE9F0)
private val BackupSurface = Color(0xFFF7F9FD)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupProgressScreen(nav: NavHostController) {
    val context = LocalContext.current
    val repository = remember { BackupRepository.get(context) }
    val settings = remember { SettingsStore(context) }
    val dao = remember { com.airdrive.backup.data.db.AppDatabase.get(context).fileRecordDao() }
    val progress by repository.progress.collectAsState()
    val paused by repository.paused.collectAsState()
    val pending by dao.pendingCountFlow().collectAsState(initial = 0)
    val failed by dao.failedCountFlow().collectAsState(initial = 0)
    val uploaded by dao.uploadedCountFlow().collectAsState(initial = 0)
    val uploadedBytes by dao.uploadedBytesFlow().collectAsState(initial = 0L)
    val categoryTotals by dao.categoryTotalsFlow().collectAsState(initial = emptyList())
    val recentActivity by dao.recentActivityFlow(4).collectAsState(initial = emptyList())
    val networkPolicy by settings.networkPolicy.collectAsState(initial = NetworkPolicy.WIFI_ONLY)
    val chargingOnly by settings.chargingOnly.collectAsState(initial = false)

    // The worker and this screen share the singleton repository, so currentFileId/name/bytes are
    // live. Recent activity supplies the actual FileRecord so a real local image/video can be
    // previewed without inventing a thumbnail or changing the backup engine.
    val currentRecord = remember(recentActivity, progress.currentFileId) {
        recentActivity.firstOrNull { it.id == progress.currentFileId }
    }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentRecord?.uri, previewOpen) {
        if (previewOpen && currentRecord != null) {
            previewBitmap = MediaThumbnails.loadPreview(context, currentRecord)
        } else {
            previewBitmap = null
        }
    }

    val scanning = progress.phase == BackupPhase.SCANNING
    val percent = progress.percent
    val statusText = when {
        scanning -> "Scanning storage"
        progress.isRunning && paused -> "Paused"
        progress.isRunning -> "Uploading to Telegram"
        progress.phase == BackupPhase.FINISHED -> "Backup finished"
        else -> "Ready to back up"
    }

    Scaffold(
        containerColor = BackupSurface,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(BackupBlue),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Cloud, contentDescription = null, tint = Color.White, modifier = Modifier.size(29.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("AirDrive Backup", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                            Text(statusText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackupSurface),
                actions = {
                    Surface(shape = RoundedCornerShape(20.dp), color = BackupGreenSoft) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(BackupGreen))
                            Spacer(Modifier.width(6.dp))
                            Text("Connected", style = MaterialTheme.typography.labelSmall, color = Color(0xFF126A43), fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(onClick = { nav.navigate(Routes.BACKUP_SETTINGS) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Backup settings")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            BackupMenuItem("Backup settings") { nav.navigate(Routes.BACKUP_SETTINGS) }
                            BackupMenuItem("Telegram destination") { nav.navigate(Routes.DESTINATION) }
                            BackupMenuItem("Backup timeline") { nav.navigate(Routes.TIMELINE) }
                            BackupMenuItem("Failed uploads ($failed)") { nav.navigate(Routes.FAILED_UPLOADS) }
                            BackupMenuItem("All activity") { nav.navigate(Routes.ACTIVITY_HISTORY) }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BackupHero(
                progress = progress,
                paused = paused,
                scanning = scanning,
                onPauseResume = { repository.setPaused(!paused) },
                onCancel = {
                    progress.currentFileId?.let { id -> scope.launch { repository.cancelUpload(id) } }
                },
                onStop = {
                    WorkScheduler.pauseManual(context)
                    repository.setPaused(false)
                    nav.navigate(Routes.DASHBOARD) { popUpTo(Routes.DASHBOARD) { inclusive = true } }
                },
                onDetails = { nav.navigate(Routes.TIMELINE) }
            )

            CurrentFileCard(
                record = currentRecord,
                progress = progress,
                onOpen = {
                    if (currentRecord != null) previewOpen = true else nav.navigate(Routes.ACTIVITY_HISTORY)
                },
                onAll = { nav.navigate(Routes.ACTIVITY_HISTORY) }
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackupStat(Format.count(uploaded), "Files backed up", Icons.Default.Description, BackupGreen, BackupGreenSoft, Modifier.weight(1f))
                BackupStat(Format.count(pending), "Pending", Icons.Default.Schedule, BackupOrange, BackupOrangeSoft, Modifier.weight(1f))
                BackupStat(Format.count(failed), "Failed", Icons.Default.Warning, BackupRed, BackupRedSoft, Modifier.weight(1f))
                BackupStat(Format.bytes(uploadedBytes), "Total size", Icons.Default.Storage, BackupPurple, BackupPurpleSoft, Modifier.weight(1f))
            }

            SectionTitle("Categories", "See all") { nav.navigate(Routes.CATEGORIES_STATS) }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categoryCard(BackupCategory.PHOTOS, Icons.Default.Image, "Photos", BackupBlue, BackupBlueSoft, categoryTotals, context, nav, repository)
                categoryCard(BackupCategory.VIDEOS, Icons.Default.VideoLibrary, "Videos", BackupOrange, BackupOrangeSoft, categoryTotals, context, nav, repository)
                categoryCard(BackupCategory.PDFS, Icons.Default.Description, "PDFs", BackupCyan, BackupCyanSoft, categoryTotals, context, nav, repository)
                categoryCard(BackupCategory.AUDIO, Icons.Default.AudioFile, "Audio", BackupPurple, BackupPurpleSoft, categoryTotals, context, nav, repository)
                categoryCard(BackupCategory.WORD_EXCEL, Icons.Default.InsertDriveFile, "Documents", BackupPink, BackupPinkSoft, categoryTotals, context, nav, repository)
                categoryCard(BackupCategory.CALL_RECORDINGS, Icons.Default.Call, "Calls", BackupRed, BackupRedSoft, categoryTotals, context, nav, repository)
                categoryCard(BackupCategory.OTHER_FILES, Icons.Default.MoreHoriz, "Other", Color(0xFF64748B), Color(0xFFE9EEF5), categoryTotals, context, nav, repository)
            }

            DetailCard(
                icon = Icons.Default.Send,
                iconTint = BackupBlue,
                title = "Telegram destination",
                subtitle = "Saved Messages • ${Format.bytes(uploadedBytes)} used",
                action = "Change",
                onClick = { nav.navigate(Routes.DESTINATION) }
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailCard(
                    icon = Icons.Default.Settings,
                    iconTint = BackupBlue,
                    title = "Backup settings",
                    subtitle = "Folders, file types, network",
                    action = "",
                    modifier = Modifier.weight(1f),
                    onClick = { nav.navigate(Routes.BACKUP_SETTINGS) }
                )
                DetailCard(
                    icon = Icons.Default.Schedule,
                    iconTint = BackupBlue,
                    title = "Schedule backup",
                    subtitle = "Automatic backup rules",
                    action = "",
                    modifier = Modifier.weight(1f),
                    onClick = { nav.navigate(Routes.BACKUP_SETTINGS) }
                )
            }

            SectionTitle("Recent activity", "See all") { nav.navigate(Routes.ACTIVITY_HISTORY) }
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    recentActivity.forEachIndexed { index, record ->
                        RecentFileRow(record, index == recentActivity.lastIndex) {
                            nav.navigate(Routes.ACTIVITY_HISTORY)
                        }
                    }
                    if (recentActivity.isEmpty()) {
                        Text("No activity yet", Modifier.padding(vertical = 18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            DetailCard(
                icon = Icons.Default.Security,
                iconTint = BackupBlue,
                title = "Your data is safe with Telegram",
                subtitle = "Private, encrypted and always accessible.",
                action = "Learn more",
                onClick = { nav.navigate(Routes.TELEGRAM_SETTINGS) }
            )

            if (!progress.isRunning && pending > 0) {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = BackupOrangeSoft)
                ) {
                    Text(
                        "$pending file(s) are queued. Automatic runs wait for ${waitingFor(networkPolicy, chargingOnly)}.",
                        Modifier.padding(15.dp),
                        color = Color(0xFF7A4A00),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
        }
    }

    if (previewOpen) {
        CurrentFilePreviewDialog(
            record = currentRecord,
            progress = progress,
            bitmap = previewBitmap,
            onDismiss = { previewOpen = false },
            onActivity = { previewOpen = false; nav.navigate(Routes.ACTIVITY_HISTORY) }
        )
    }
}

@Composable
private fun BackupHero(
    progress: com.airdrive.backup.data.repo.UploadProgress,
    paused: Boolean,
    scanning: Boolean,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit,
    onStop: () -> Unit,
    onDetails: () -> Unit
) {
    val fraction = progress.fraction.coerceIn(0f, 1f)
    val percent = progress.percent
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = BackupBlueSoft)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(118.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { if (scanning) 0.18f else fraction },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 10.dp,
                        trackColor = Color(0xFFD4E3F8),
                        color = BackupBlue
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$percent%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = BackupBlueDark)
                        Text("Backing up", style = MaterialTheme.typography.labelMedium, color = BackupBlueDark)
                        Text("${progress.doneFiles} / ${progress.totalFiles}", style = MaterialTheme.typography.labelSmall, color = BackupBlueDark)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Backup in progress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = BackupBlueDark, modifier = Modifier.weight(1f))
                        Surface(shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = .75f)) {
                            Text(if (paused) "Ⅱ Paused" else "● Uploading", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = BackupBlue, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Your files are being securely uploaded to your Telegram account.", color = Color(0xFF5C7094), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        HeroMetric("↑", Format.bytes(progress.effectiveBytes), "of ${Format.bytes(progress.totalBytesQueued)}", Modifier.weight(1f))
                        HeroMetric("ϟ", if (progress.bytesPerSecond > 0) "${Format.bytes(progress.bytesPerSecond)}/s" else "—", "Speed", Modifier.weight(1f))
                        HeroMetric("◷", formatEta(progress.etaSeconds), "ETA", Modifier.weight(1f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPauseResume, Modifier.weight(1.3f).height(52.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = BackupBlue)) {
                    Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (paused) "Resume backup" else "Pause", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onCancel, enabled = progress.currentFileId != null, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = BackupRed)) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Cancel file")
                }
                OutlinedButton(onClick = onStop, Modifier.weight(.9f).height(52.dp), shape = RoundedCornerShape(17.dp)) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }
            OutlinedButton(onClick = onDetails, Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(15.dp)) {
                Icon(Icons.Default.List, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("View full backup details")
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.ArrowForward, contentDescription = null)
            }
        }
    }
}

@Composable
private fun HeroMetric(icon: String, value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = .82f)) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
            Text(icon, color = BackupBlue, fontWeight = FontWeight.Bold)
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CurrentFileCard(
    record: FileRecord?,
    progress: com.airdrive.backup.data.repo.UploadProgress,
    onOpen: () -> Unit,
    onAll: () -> Unit
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Current file", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = BackupBlueDark, modifier = Modifier.weight(1f))
                Text("${progress.doneFiles} / ${progress.totalFiles}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onAll) { Text("View all") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileThumbnail(record, Modifier.size(78.dp))
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(progress.currentFileName ?: record?.displayName ?: "Preparing next file…", maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (record != null) "${categoryLabel(record.category)} • ${Format.bytes(record.sizeBytes)}" else "Waiting for the next file",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (progress.currentFileBytes > 0) (progress.currentFileUploadedBytes.toFloat() / progress.currentFileBytes).coerceIn(0f, 1f) else 0f },
                        Modifier.fillMaxWidth(),
                        color = BackupBlue,
                        trackColor = BackupBlueSoft
                    )
                    Text("${Format.bytes(progress.currentFileUploadedBytes)} / ${Format.bytes(progress.currentFileBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoPill(Icons.Default.InsertDriveFile, "${extensionLabel(record?.displayName ?: progress.currentFileName.orEmpty())}", Modifier.weight(1f))
                InfoPill(Icons.Default.Folder, record?.let { folderLabel(it.uri) } ?: "Local storage", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FileThumbnail(record: FileRecord?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(17.dp)).background(BackupBlueSoft), contentAlignment = Alignment.Center) {
        val bitmap = remember(record?.uri) { record?.let(MediaThumbnails::peek) }
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(categoryIcon(record?.category), contentDescription = null, tint = BackupBlue, modifier = Modifier.size(35.dp))
        }
    }
}

@Composable
private fun CurrentFilePreviewDialog(
    record: FileRecord?,
    progress: com.airdrive.backup.data.repo.UploadProgress,
    bitmap: Bitmap?,
    onDismiss: () -> Unit,
    onActivity: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onActivity) { Text("Open activity") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(record?.displayName ?: progress.currentFileName ?: "Current file", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), contentDescription = "Current file preview", Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Fit)
                } else {
                    Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)).background(BackupBlueSoft), contentAlignment = Alignment.Center) {
                        Icon(categoryIcon(record?.category), contentDescription = null, tint = BackupBlue, modifier = Modifier.size(64.dp))
                    }
                }
                Text(record?.let { "${categoryLabel(it.category)} • ${Format.bytes(it.sizeBytes)}" } ?: "Waiting for local file metadata", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Uploaded ${Format.bytes(progress.currentFileUploadedBytes)} of ${Format.bytes(progress.currentFileBytes)}", fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { if (progress.currentFileBytes > 0) (progress.currentFileUploadedBytes.toFloat() / progress.currentFileBytes).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth())
            }
        }
    )
}

@Composable
private fun BackupStat(value: String, label: String, icon: ImageVector, tint: Color, soft: Color, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(soft), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp)) }
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SectionTitle(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text(action, color = BackupBlue) }
    }
}

@Composable
private fun categoryCard(
    category: BackupCategory,
    icon: ImageVector,
    label: String,
    tint: Color,
    soft: Color,
    totals: List<CategoryTotals>,
    context: android.content.Context,
    nav: NavHostController,
    repository: BackupRepository
) {
    val total = totals.firstOrNull { it.category == category }
    val count = total?.total ?: 0
    val bytes = total?.totalBytes ?: 0L
    val uploaded = total?.uploaded ?: 0
    Card(
        Modifier.width(126.dp).clickable { nav.navigate("${Routes.CATEGORY_DETAIL}/${category.name}") },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(soft), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(23.dp)) }
            Spacer(Modifier.height(7.dp))
            Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${Format.count(count)} files", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(Format.bytes(bytes), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(7.dp))
            LinearProgressIndicator(progress = { if (count > 0) (uploaded.toFloat() / count).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth(), color = tint, trackColor = Color(0xFFE6EBF3))
            Text("Uploading ${Format.count((count - uploaded).coerceAtLeast(0))}", color = tint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun DetailCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    action: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(Modifier.fillMaxWidth().then(modifier).clickable(onClick = onClick), shape = RoundedCornerShape(21.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(BackupBlueSoft), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(25.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (action.isNotBlank()) Text(action, color = BackupBlue, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = BackupBlue)
        }
    }
}

@Composable
private fun RecentFileRow(record: FileRecord, last: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            FileThumbnail(record, Modifier.size(42.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(record.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text("${statusLabel(record.status)} • ${Format.bytes(record.sizeBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = BackupBlue)
        }
        if (!last) HorizontalDivider(color = Color(0xFFE8EDF5))
    }
}

@Composable
private fun InfoPill(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(13.dp), color = BackupBlueSoft) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = BackupBlue, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun BackupMenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

private fun categoryLabel(category: BackupCategory): String = when (category) {
    BackupCategory.PHOTOS -> "Photos"
    BackupCategory.VIDEOS -> "Videos"
    BackupCategory.PDFS -> "PDFs"
    BackupCategory.WORD_EXCEL -> "Documents"
    BackupCategory.AUDIO -> "Audio"
    BackupCategory.CALL_RECORDINGS -> "Calls"
    BackupCategory.OTHER_FILES -> "Other"
}

private fun categoryIcon(category: BackupCategory?): ImageVector = when (category) {
    BackupCategory.PHOTOS -> Icons.Default.Image
    BackupCategory.VIDEOS -> Icons.Default.VideoLibrary
    BackupCategory.PDFS -> Icons.Default.Description
    BackupCategory.WORD_EXCEL -> Icons.Default.InsertDriveFile
    BackupCategory.AUDIO -> Icons.Default.AudioFile
    BackupCategory.CALL_RECORDINGS -> Icons.Default.Call
    BackupCategory.OTHER_FILES, null -> Icons.Default.InsertDriveFile
}

private fun statusLabel(status: UploadStatus): String = when (status) {
    UploadStatus.PENDING -> "Queued"
    UploadStatus.UPLOADING -> "Uploading"
    UploadStatus.UPLOADED -> "Backed up"
    UploadStatus.FAILED -> "Failed"
    UploadStatus.SKIPPED -> "Skipped"
    UploadStatus.CANCELLED -> "Cancelled"
}

private fun extensionLabel(name: String): String = name.substringAfterLast('.', "FILE").uppercase().take(6)

private fun folderLabel(uri: String): String {
    val path = uri.substringAfter("file://", uri)
    val parts = path.split('/').filter { it.isNotBlank() }
    return if (parts.size >= 2) parts.takeLast(2).dropLast(1).joinToString("/") else "Local storage"
}

private fun formatEta(seconds: Long): String {
    if (seconds <= 0L) return "—"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

private fun waitingFor(policy: NetworkPolicy, chargingOnly: Boolean): String {
    val network = when (policy) {
        NetworkPolicy.WIFI_ONLY -> "Wi-Fi"
        NetworkPolicy.NOT_ROAMING -> "a non-roaming connection"
        NetworkPolicy.ANY -> "any connection"
    }
    return if (chargingOnly) "$network and charging" else network
}
