package com.airdrive.backup.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.*
import com.airdrive.backup.data.prefs.DestinationMode
import com.airdrive.backup.data.prefs.NetworkPolicy
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.DeviceState
import com.airdrive.backup.util.StorageAccess
import com.airdrive.backup.work.WorkScheduler
import java.text.SimpleDateFormat
import java.util.*

// AirDrive reference palette: blue primary + distinct semantic category/status colors.
private val AirBlue = Color(0xFF2F6FEA)
private val AirBlueDark = Color(0xFF174A9C)
private val AirBlueLight = Color(0xFFEAF2FF)
private val AirSurface = Color(0xFFF7F9FD)
private val AirGreen = Color(0xFF20A463)
private val AirGreenLight = Color(0xFFE5F7EE)
private val AirOrange = Color(0xFFF59E0B)
private val AirOrangeLight = Color(0xFFFFF4DD)
private val AirRed = Color(0xFFE5484D)
private val AirRedLight = Color(0xFFFFE8E8)
private val AirPurple = Color(0xFF7C3AED)
private val AirPurpleLight = Color(0xFFF0E8FF)
private val AirCyan = Color(0xFF18B8C8)
private val AirCyanLight = Color(0xFFE2F8FA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(nav: NavHostController) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val settings = remember { SettingsStore(context) }
    val repository = remember { BackupRepository.get(context) }
    val dao = db.fileRecordDao()

    val uploadedCount by dao.uploadedCountFlow().collectAsState(initial = 0)
    val pendingCount by dao.pendingCountFlow().collectAsState(initial = 0)
    val failedCount by dao.failedCountFlow().collectAsState(initial = 0)
    val uploadedBytes by dao.uploadedBytesFlow().collectAsState(initial = 0L)
    val lastBackup by dao.lastBackupTimeFlow().collectAsState(initial = null)
    val categoryTotals by dao.categoryTotalsFlow().collectAsState(initial = emptyList())
    val destination by settings.destination.collectAsState(initial = null)
    val progress by repository.progress.collectAsState()
    val paused by repository.paused.collectAsState()
    val missingCount by remember { repository.missingCountFlow() }.collectAsState(initial = 0)
    val cleanupTotals by remember { repository.cleanupTotalsFlow() }.collectAsState(initial = emptyList())
    val reclaimableBytes = remember(cleanupTotals) { cleanupTotals.sumOf { it.bytes } }
    val verifyProblems by remember { repository.verifyProblemCountFlow() }.collectAsState(initial = 0)
    val versionedFiles by remember { repository.versionedFileCountFlow() }.collectAsState(initial = 0)

    var hasAccess by remember { mutableStateOf(StorageAccess.hasFullAccess(context)) }
    val autoBackup by settings.autoBackupEnabled.collectAsState(initial = false)
    val chargingOnly by settings.chargingOnly.collectAsState(initial = false)
    val batteryConscious by settings.batteryConscious.collectAsState(initial = true)
    val networkPolicy by settings.networkPolicy.collectAsState(initial = NetworkPolicy.ANY)
    var charging by remember { mutableStateOf(DeviceState.isCharging(context)) }
    var batteryLow by remember { mutableStateOf(DeviceState.isBatteryLow(context)) }
    var unmetered by remember { mutableStateOf(DeviceState.isUnmetered(context)) }
    var menuOpen by remember { mutableStateOf(false) }

    OnResumeEffect {
        hasAccess = StorageAccess.hasFullAccess(context)
        charging = DeviceState.isCharging(context)
        batteryLow = DeviceState.isBatteryLow(context)
        unmetered = DeviceState.isUnmetered(context)
    }

    val waitingFor = when {
        !autoBackup || progress.isRunning -> null
        chargingOnly && !charging -> "Automatic backups are set to run only while charging."
        batteryConscious && batteryLow -> "Automatic backups are paused while the battery is low."
        networkPolicy == NetworkPolicy.WIFI_ONLY && !unmetered -> "Automatic backups are waiting for Wi-Fi."
        else -> null
    }
    val fraction = progress.fraction.coerceIn(0f, 1f)
    val title = if (progress.isRunning) "Backup in progress" else "Ready to back up"
    val subtitle = if (progress.isRunning) progress.currentFileName?.let { "Uploading $it" } ?: "Uploading files…" else "Your files are protected with AirDrive"

    Scaffold(
        containerColor = AirSurface,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(AirBlue), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Cloud, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("AirDrive", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                            Text("Your files. Backed up. Always with you.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AirSurface),
                actions = {
                    Surface(shape = RoundedCornerShape(22.dp), color = AirGreenLight) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(AirGreen))
                            Spacer(Modifier.width(6.dp))
                            Text(if (destination?.needsSetup == false) "Connected" else "Setup needed", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF126A43))
                        }
                    }
                    IconButton(onClick = { nav.navigate(Routes.BACKUP_SETTINGS) }) { Icon(Icons.Default.Settings, "Backup settings") }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More options") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        MenuItem("Backup destination") { nav.navigate(Routes.DESTINATION) }
                        MenuItem("Channel configuration") { nav.navigate(Routes.CHANNEL_CONFIG) }
                        MenuItem("Storage access") { nav.navigate(Routes.STORAGE_ACCESS) }
                        MenuItem("Search backups") { nav.navigate(Routes.SEARCH) }
                        MenuItem("Photo gallery") { nav.navigate(Routes.GALLERY) }
                        MenuItem("Backup timeline") { nav.navigate(Routes.TIMELINE) }
                        MenuItem(if (missingCount > 0) "Deleted files ($missingCount)" else "Deleted files") { nav.navigate(Routes.DELETED_FILES) }
                        MenuItem("Restore from Telegram") { nav.navigate(Routes.RESTORE) }
                        MenuItem("Restore from old device") { nav.navigate(Routes.MIGRATE) }
                        MenuItem(if (reclaimableBytes > 0) "Storage cleanup (${formatBytes(reclaimableBytes)})" else "Storage cleanup") { nav.navigate(Routes.CLEANUP) }
                        MenuItem(if (verifyProblems > 0) "Backup verification ($verifyProblems)" else "Backup verification") { nav.navigate(Routes.VERIFY) }
                        MenuItem(if (versionedFiles > 0) "File history ($versionedFiles)" else "File history") { nav.navigate(Routes.FILE_HISTORY) }
                        MenuItem("Failed uploads") { nav.navigate(Routes.FAILED_UPLOADS) }
                        MenuItem("About") { nav.navigate(Routes.ABOUT) }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!hasAccess) NoticeCard("Storage access is off", "Allow AirDrive to scan your files.", "Fix this") { nav.navigate(Routes.STORAGE_ACCESS) }
            if (destination?.needsSetup == true) NoticeCard("No Telegram destination yet", "Choose Saved Messages or a private channel.", "Choose") { nav.navigate(Routes.DESTINATION) }
            waitingFor?.let { NoticeCard("Automatic backup is waiting", it, "Settings") { nav.navigate(Routes.BACKUP_SETTINGS) } }

            BackupHeroCard(
                title = title,
                subtitle = subtitle,
                fraction = fraction,
                doneFiles = progress.doneFiles,
                totalFiles = progress.totalFiles,
                doneBytes = progress.effectiveBytes,
                totalBytes = progress.totalBytesQueued,
                paused = paused,
                running = progress.isRunning,
                onPause = { repository.setPaused(!paused) },
                onResume = { repository.setPaused(false) },
                onDetails = { nav.navigate(Routes.BACKUP_PROGRESS) }
            )

            // Reference uses a compact 2 x 2 statistics block with strong semantic colors.
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    StatCard(uploadedCount.toString(), "Files backed up", AirGreen, AirGreenLight, Modifier.weight(1f))
                    StatCard(pendingCount.toString(), "Pending", AirOrange, AirOrangeLight, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    StatCard(failedCount.toString(), "Failed", AirRed, AirRedLight, Modifier.weight(1f))
                    StatCard(formatBytes(uploadedBytes), "Storage used", AirPurple, AirPurpleLight, Modifier.weight(1f))
                }
            }

            if (!progress.isRunning) {
                Button(onClick = { repository.setPaused(false); WorkScheduler.runNow(context); nav.navigate(Routes.BACKUP_PROGRESS) }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = AirBlue)) {
                    Icon(Icons.Default.Cloud, null, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(8.dp)); Text("Back up now", fontWeight = FontWeight.Bold)
                }
            }

            OutlinedButton(onClick = { nav.navigate(Routes.BACKUP_PROGRESS) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.List, null, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("View backup details"); Spacer(Modifier.weight(1f)); Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(18.dp))
            }

            SectionHeader("Current backup", "See all") { nav.navigate(Routes.BACKUP_PROGRESS) }
            CurrentFileCard(progress.currentFileName ?: if (progress.isRunning) "Preparing next file…" else "No file is uploading", if (progress.isRunning && progress.totalFiles > 0) "${progress.doneFiles} / ${progress.totalFiles} files" else "Last backup: ${formatLastBackup(lastBackup)}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Status", if (progress.isRunning) if (paused) "Paused" else "Uploading" else "Up to date", Modifier.weight(1f))
                MetricCard("Destination", destinationLabel(destination?.mode), Modifier.weight(1f))
            }

            SectionHeader("Categories", "See all") { nav.navigate(Routes.CATEGORIES_STATS) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryCard(BackupCategory.PHOTOS, Icons.Default.Image, AirBlue, AirBlueLight, categoryTotals, Modifier.weight(1f), nav, repository, context)
                CategoryCard(BackupCategory.VIDEOS, Icons.Default.VideoLibrary, AirOrange, AirOrangeLight, categoryTotals, Modifier.weight(1f), nav, repository, context)
                CategoryCard(BackupCategory.DOCUMENTS, Icons.Default.Description, AirCyan, AirCyanLight, categoryTotals, Modifier.weight(1f), nav, repository, context)
                CategoryCard(BackupCategory.AUDIO, Icons.Default.AudioFile, AirPurple, AirPurpleLight, categoryTotals, Modifier.weight(1f), nav, repository, context)
            }

            Card(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.DESTINATION) }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Telegram destination", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(9.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(50.dp).clip(CircleShape).background(AirBlueLight), contentAlignment = Alignment.Center) { Icon(Icons.Default.Send, null, tint = AirBlue, modifier = Modifier.size(27.dp)) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text("AirDrive Backups", fontWeight = FontWeight.SemiBold); Text("${destinationLabel(destination?.mode)} • ${formatBytes(uploadedBytes)} used", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Icon(Icons.Default.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = AirBlueLight) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFFD6E7FF)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Security, null, tint = AirBlue, modifier = Modifier.size(24.dp)) }
                    Spacer(Modifier.width(11.dp)); Column(Modifier.weight(1f)) { Text("Your data is safe with Telegram", fontWeight = FontWeight.Bold, color = AirBlueDark); Text("Private, encrypted and always accessible.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { nav.navigate(Routes.ABOUT) }) { Text("Learn more") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable private fun MenuItem(text: String, onClick: () -> Unit) = DropdownMenuItem(text = { Text(text) }, onClick = onClick)

@Composable
private fun BackupHeroCard(title: String, subtitle: String, fraction: Float, doneFiles: Int, totalFiles: Int, doneBytes: Long, totalBytes: Long, paused: Boolean, running: Boolean, onPause: () -> Unit, onResume: () -> Unit, onDetails: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = AirBlueLight)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxSize(), strokeWidth = 10.dp, color = AirBlue, trackColor = Color(0xFFD4E3F7))
                    Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFF102B63))
                }
                Spacer(Modifier.width(17.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF102B63))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                    Text(if (totalFiles > 0) "$doneFiles / $totalFiles files" else "No files queued", fontWeight = FontWeight.SemiBold)
                    if (totalBytes > 0) Text("${formatBytes(doneBytes)} / ${formatBytes(totalBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (running) Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.height(8.dp)); FilledTonalIconButton(onClick = if (paused) onResume else onPause, modifier = Modifier.size(38.dp)) { Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "Resume" else "Pause") }; Spacer(Modifier.width(7.dp)); Text(if (paused) "Paused" else "Uploading…", style = MaterialTheme.typography.labelMedium, color = AirBlue)
                    }
                }
            }
            Spacer(Modifier.height(13.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(onClick = if (running) if (paused) onResume else onPause else onDetails, Modifier.weight(1f).height(47.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = AirBlue)) { Text(if (running) if (paused) "Resume backup" else "Pause backup" else "Backup details") }
                OutlinedButton(onClick = onDetails, Modifier.weight(1f).height(47.dp), shape = RoundedCornerShape(15.dp)) { Text("View details"); Spacer(Modifier.width(5.dp)); Icon(Icons.Default.ArrowForward, null, Modifier.size(16.dp)) }
            }
        }
    }
}

@Composable private fun StatCard(value: String, label: String, accent: Color, iconBackground: Color, modifier: Modifier) {
    Card(modifier.heightIn(min = 88.dp), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(iconBackground), contentAlignment = Alignment.Center) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF182033), maxLines = 1)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

@Composable private fun SectionHeader(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, Modifier.weight(1f)); TextButton(onClick) { Text(action, color = AirBlue) } }
}

@Composable private fun CurrentFileCard(name: String, meta: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(49.dp).clip(RoundedCornerShape(13.dp)).background(AirBlueLight), contentAlignment = Alignment.Center) { Icon(Icons.Default.Description, null, tint = AirBlue, Modifier.size(26.dp)) }
            Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.Bold, maxLines = 1); Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(Icons.Default.ArrowForward, null, tint = AirBlue)
        }
    }
}

@Composable private fun MetricCard(title: String, value: String, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(13.dp)) { Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(3.dp)); Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1) } }
}

@Composable
private fun CategoryCard(category: BackupCategory, icon: ImageVector, accent: Color, iconBackground: Color, totals: List<CategoryTotals>, modifier: Modifier, nav: NavHostController, repository: BackupRepository, context: android.content.Context) {
    val row = totals.find { it.category == category }
    val total = row?.total ?: 0
    val uploaded = row?.uploaded ?: 0
    val pending = (total - uploaded).coerceAtLeast(0)
    val progress = if (total > 0) (uploaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    Card(modifier.clickable { nav.navigate("${Routes.CATEGORY_DETAIL}/${category.name}") }, shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(iconBackground), contentAlignment = Alignment.Center) { Icon(icon, null, tint = accent, Modifier.size(23.dp)) }
            Spacer(Modifier.height(6.dp)); Text(categoryLabel(category), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1); Text("$total files", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(formatBytes(row?.totalBytes ?: 0L), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(6.dp)); LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = accent, trackColor = Color(0xFFE4EAF3))
            TextButton(enabled = pending > 0, onClick = { repository.setPaused(false); WorkScheduler.runNowCategory(context, category); nav.navigate(Routes.BACKUP_PROGRESS) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(29.dp)) { Text(if (pending > 0) "Upload $pending" else "Up to date", style = MaterialTheme.typography.labelSmall, color = accent) }
        }
    }
}

@Composable private fun NoticeCard(title: String, message: String, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; TextButton(onClick) { Text(action, color = AirBlue) } } }
}

private fun destinationLabel(mode: DestinationMode?): String = when (mode) {
    DestinationMode.SAVED_MESSAGES -> "Saved Messages"
    DestinationMode.SINGLE_CHAT -> "Private channel"
    DestinationMode.PER_CATEGORY -> "Category channels"
    null -> "Not configured"
}

private fun formatLastBackup(millis: Long?): String = if (millis == null) "Never" else SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(millis))
