package com.airdrive.backup.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.prefs.NetworkPolicy
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.repo.BackupPhase
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.Format
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { BackupRepository.get(context) }
    val settings = remember { SettingsStore(context) }
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val progress by repository.progress.collectAsState()
    val paused by repository.paused.collectAsState()
    val pending by dao.pendingCountFlow().collectAsState(initial = 0)
    val failed by dao.failedCountFlow().collectAsState(initial = 0)
    val uploaded by dao.uploadedCountFlow().collectAsState(initial = 0)
    val uploadedBytes by dao.uploadedBytesFlow().collectAsState(initial = 0L)
    val policy by settings.networkPolicy.collectAsState(initial = NetworkPolicy.WIFI_ONLY)
    val chargingOnly by settings.chargingOnly.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }

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
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Blue),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Cloud, null, tint = Color.White)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("AirDrive Backup", fontWeight = FontWeight.Bold)
                            Text(
                                status,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
                actions = {
                    Surface(shape = RoundedCornerShape(18.dp), color = GreenSoft) {
                        Row(
                            Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Green))
                            Spacer(Modifier.width(5.dp))
                            Text("Connected", style = MaterialTheme.typography.labelSmall, color = Color(0xFF126A43), fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(onClick = { nav.navigate(Routes.BACKUP_SETTINGS) }) {
                        Icon(Icons.Default.Settings, "Backup settings")
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, "More options")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            ProgressMenuItem("Backup settings") { nav.navigate(Routes.BACKUP_SETTINGS) }
                            ProgressMenuItem("Telegram destination") { nav.navigate(Routes.DESTINATION) }
                            ProgressMenuItem("Backup timeline") { nav.navigate(Routes.TIMELINE) }
                            ProgressMenuItem("Failed uploads ($failed)") { nav.navigate(Routes.FAILED_UPLOADS) }
                            ProgressMenuItem("All activity") { nav.navigate(Routes.ACTIVITY_HISTORY) }
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
            ProgressHero(
                progress = progress,
                paused = paused,
                scanning = scanning,
                onPause = { repository.setPaused(!paused) },
                onCancel = {
                    progress.currentFileId?.let { id -> scope.launch { repository.cancelUpload(id) } }
                },
                onStop = {
                    WorkScheduler.pauseManual(context)
                    repository.setPaused(false)
                    nav.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.DASHBOARD) { inclusive = true }
                    }
                },
                onDetails = { nav.navigate(Routes.TIMELINE) }
            )

            CurrentUploadCard(progress = progress, onActivity = { nav.navigate(Routes.ACTIVITY_HISTORY) })

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressStat(Format.count(uploaded), "Backed up", Icons.Default.CheckCircle, Green, GreenSoft, Modifier.weight(1f))
                ProgressStat(Format.count(pending), "Pending", Icons.Default.Schedule, Orange, OrangeSoft, Modifier.weight(1f))
                ProgressStat(Format.count(failed), "Failed", Icons.Default.Warning, Red, RedSoft, Modifier.weight(1f))
                ProgressStat(Format.bytes(uploadedBytes), "Total", Icons.Default.Storage, Purple, PurpleSoft, Modifier.weight(1f))
            }

            ProgressSectionTitle("Backup details") { nav.navigate(Routes.TIMELINE) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                BackupCategoryCard("Photos", Icons.Default.Image, Blue, BlueSoft) { nav.navigate(Routes.GALLERY) }
                BackupCategoryCard("Videos", Icons.Default.VideoLibrary, Orange, OrangeSoft) { nav.navigate(Routes.GALLERY) }
                BackupCategoryCard("PDFs", Icons.Default.PictureAsPdf, Cyan, CyanSoft) { nav.navigate(Routes.CATEGORIES_STATS) }
                BackupCategoryCard("Audio", Icons.Default.AudioFile, Purple, PurpleSoft) { nav.navigate(Routes.CATEGORIES_STATS) }
                BackupCategoryCard("Documents", Icons.Default.InsertDriveFile, Pink, PinkSoft) { nav.navigate(Routes.CATEGORIES_STATS) }
                BackupCategoryCard("Calls", Icons.Default.Call, Red, RedSoft) { nav.navigate(Routes.CATEGORIES_STATS) }
                BackupCategoryCard("Other", Icons.Default.MoreHoriz, Color(0xFF64748B), Color(0xFFE9EEF5)) { nav.navigate(Routes.CATEGORIES_STATS) }
            }

            ActionCard(Icons.Default.Send, Blue, "Telegram destination", "Your selected Telegram destination", "Change") {
                nav.navigate(Routes.DESTINATION)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    ActionCard(Icons.Default.Settings, Blue, "Backup settings", "Folders, file types and network", "Open") {
                        nav.navigate(Routes.BACKUP_SETTINGS)
                    }
                }
                Box(Modifier.weight(1f)) {
                    ActionCard(Icons.Default.Schedule, Blue, "Schedule backup", "Automatic backup rules", "Open") {
                        nav.navigate(Routes.BACKUP_SETTINGS)
                    }
                }
            }

            ProgressSectionTitle("Recent activity") { nav.navigate(Routes.ACTIVITY_HISTORY) }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp)) {
                    ActivitySummaryRow(Icons.Default.CloudUpload, Blue, "Backup status", status)
                    ActivitySummaryRow(Icons.Default.Description, Green, "Files backed up", Format.count(uploaded))
                    ActivitySummaryRow(Icons.Default.Schedule, Orange, "Files waiting", Format.count(pending))
                    ActivitySummaryRow(Icons.Default.Warning, Red, "Failed uploads", Format.count(failed), true)
                }
            }

            ActionCard(Icons.Default.Security, Blue, "Your data is safe with Telegram", "Private, encrypted and controlled by your account", "Learn more") {
                nav.navigate(Routes.TELEGRAM_SETTINGS)
            }

            if (!progress.isRunning && pending > 0) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = OrangeSoft)) {
                    Text(
                        "$pending file(s) are queued. Automatic runs wait for ${networkText(policy, chargingOnly)}.",
                        Modifier.padding(15.dp),
                        color = Color(0xFF7A4A00)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ProgressHero(
    progress: com.airdrive.backup.data.repo.UploadProgress,
    paused: Boolean,
    scanning: Boolean,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onStop: () -> Unit,
    onDetails: () -> Unit
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = BlueSoft)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { if (scanning) 0.18f else progress.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 10.dp,
                        trackColor = Color(0xFFD4E3F8),
                        color = Blue
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${progress.percent}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = BlueDark)
                        Text(if (scanning) "Scanning" else "Backing up", style = MaterialTheme.typography.labelMedium, color = BlueDark)
                        Text("${progress.doneFiles} / ${progress.totalFiles}", style = MaterialTheme.typography.labelSmall, color = BlueDark)
                    }
                }
                Spacer(Modifier.width(15.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            paused -> "Backup paused"
                            scanning -> "Scanning your files"
                            progress.isRunning -> "Backup in progress"
                            progress.phase == BackupPhase.FINISHED -> "Backup finished"
                            else -> "Ready to back up"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = BlueDark
                    )
                    Spacer(Modifier.height(5.dp))
                    Text("Your files are being securely uploaded to your Telegram account.", color = Color(0xFF5C7094))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        MiniMetric("Uploaded", Format.bytes(progress.effectiveBytes), Modifier.weight(1f))
                        MiniMetric("Speed", if (progress.bytesPerSecond > 0) "${Format.bytes(progress.bytesPerSecond)}/s" else "—", Modifier.weight(1f))
                        MiniMetric("ETA", etaText(progress.etaSeconds), Modifier.weight(1f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPause, modifier = Modifier.weight(1.3f).height(50.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)) {
                    Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (paused) "Resume" else "Pause", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onCancel, enabled = progress.currentFileId != null, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Red)) {
                    Icon(Icons.Default.Close, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Cancel")
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(.8f).height(50.dp), shape = RoundedCornerShape(17.dp)) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }
            OutlinedButton(onClick = onDetails, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(15.dp)) {
                Icon(Icons.Default.List, null)
                Spacer(Modifier.width(7.dp))
                Text("View full backup details")
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.ArrowForward, null)
            }
        }
    }
}

@Composable
private fun MiniMetric(label: String, value: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = .82f)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Text(label, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CurrentUploadCard(progress: com.airdrive.backup.data.repo.UploadProgress, onActivity: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)).background(CyanSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CloudUpload, null, tint = Cyan, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Current file", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(progress.currentFileName?.takeIf { it.isNotBlank() } ?: "Waiting for next file", maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onActivity) { Icon(Icons.Default.OpenInNew, "Open activity") }
            }
            if (progress.currentFileBytes > 0L) {
                LinearProgressIndicator(
                    progress = { progress.currentFileFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Blue
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(Format.bytes(progress.currentFileUploadedBytes), style = MaterialTheme.typography.labelSmall, color = Blue)
                    Text(Format.bytes(progress.currentFileBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("No file is currently uploading.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProgressStat(value: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, background: Color, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = background)) {
        Column(Modifier.padding(11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(4.dp))
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(label, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun BackupCategoryCard(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, background: Color, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.width(104.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = background)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(27.dp))
            Spacer(Modifier.height(7.dp))
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ActionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, subtitle: String, action: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(BlueSoft), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (action.isNotBlank()) Text(action, color = tint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ProgressSectionTitle(title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text("View all") }
    }
}

@Composable
private fun ActivitySummaryRow(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, value: String, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(11.dp))
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Text(value, color = if (last) Red else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (last) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun ProgressMenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

private fun etaText(seconds: Long): String = when {
    seconds <= 0L -> "—"
    seconds < 60L -> "${seconds}s"
    seconds < 3600L -> "${seconds / 60}m"
    else -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
}

private fun networkText(policy: NetworkPolicy, chargingOnly: Boolean): String = when {
    chargingOnly -> "Wi-Fi/charging"
    policy == NetworkPolicy.WIFI_ONLY -> "Wi-Fi"
    policy == NetworkPolicy.NOT_ROAMING -> "Wi-Fi/mobile without roaming"
    else -> "any allowed connection"
}
