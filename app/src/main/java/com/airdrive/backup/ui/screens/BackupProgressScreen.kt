package com.airdrive.backup.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.airdrive.backup.data.repo.UploadProgress
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.Format
import com.airdrive.backup.work.WorkScheduler
import kotlinx.coroutines.launch

private val Blue = Color(0xFF2F6FEA)
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
    val scanning = progress.phase == BackupPhase.SCANNING
    val status = when {
        scanning -> "Scanning storage"
        progress.isRunning && paused -> "Paused"
        progress.isRunning -> "Uploading to Telegram"
        progress.phase == BackupPhase.FINISHED -> "Backup finished"
        else -> "Ready to back up"
    }

    Scaffold(
        containerColor = Color(0xFFF7F9FD),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text("AirDrive Backup", fontWeight = FontWeight.Bold)
                        Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.BACKUP_SETTINGS) }) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Backup settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ProgressCard(
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

            CurrentFileCard(progress = progress) {
                nav.navigate(Routes.ACTIVITY_HISTORY)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(Format.count(uploaded), "Backed up", Icons.Default.CheckCircle, GreenSoft, Green, Modifier.weight(1f))
                StatCard(Format.count(pending), "Pending", Icons.Default.Schedule, OrangeSoft, Orange, Modifier.weight(1f))
                StatCard(Format.count(failed), "Failed", Icons.Default.Warning, RedSoft, Red, Modifier.weight(1f))
            }

            Text("Backup details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailCard("Photos", Icons.Default.Image, BlueSoft, Blue, Modifier.weight(1f)) { nav.navigate(Routes.GALLERY) }
                DetailCard("Videos", Icons.Default.VideoLibrary, OrangeSoft, Orange, Modifier.weight(1f)) { nav.navigate(Routes.GALLERY) }
                DetailCard("Files", Icons.Default.InsertDriveFile, PurpleSoft, Purple, Modifier.weight(1f)) { nav.navigate(Routes.CATEGORIES_STATS) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailCard("PDFs", Icons.Default.PictureAsPdf, CyanSoft, Cyan, Modifier.weight(1f)) { nav.navigate(Routes.CATEGORIES_STATS) }
                DetailCard("Audio", Icons.Default.AudioFile, PurpleSoft, Purple, Modifier.weight(1f)) { nav.navigate(Routes.CATEGORIES_STATS) }
                DetailCard("Calls", Icons.Default.Call, RedSoft, Red, Modifier.weight(1f)) { nav.navigate(Routes.CATEGORIES_STATS) }
            }

            ActionCard("Telegram destination", "Your selected Telegram destination", Icons.Default.Send) {
                nav.navigate(Routes.DESTINATION)
            }
            ActionCard("Backup settings", "Folders, file types and network", Icons.Default.Settings) {
                nav.navigate(Routes.BACKUP_SETTINGS)
            }
            ActionCard("Recent activity", "See uploaded, pending and failed files", Icons.Default.List) {
                nav.navigate(Routes.ACTIVITY_HISTORY)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Backup summary", fontWeight = FontWeight.Bold)
                    SummaryRow("Uploaded", Format.count(uploaded), Green)
                    SummaryRow("Pending", Format.count(pending), Orange)
                    SummaryRow("Failed", Format.count(failed), Red)
                    SummaryRow("Uploaded size", Format.bytes(uploadedBytes), Purple)
                    if (!progress.isRunning && pending > 0) {
                        Text(
                            "$pending file(s) are queued. Automatic runs wait for ${networkText(policy, chargingOnly)}.",
                            color = Color(0xFF7A4A00),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(
    progress: UploadProgress,
    paused: Boolean,
    scanning: Boolean,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onStop: () -> Unit,
    onDetails: () -> Unit
) {
    val fraction = progress.fraction.coerceIn(0f, 1f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = BlueSoft)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(106.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { if (scanning) 0.18f else fraction },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 9.dp,
                        color = Blue,
                        trackColor = Color(0xFFD4E3F8)
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${progress.percent}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Blue)
                        Text("${progress.doneFiles}/${progress.totalFiles}", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when {
                            paused -> "Backup paused"
                            scanning -> "Scanning your files"
                            progress.isRunning -> "Backup in progress"
                            progress.phase == BackupPhase.FINISHED -> "Backup finished"
                            else -> "Ready to back up"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    Text("Securely uploading to your Telegram account.", color = Color(0xFF5C7094))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${Format.bytes(progress.effectiveBytes)} uploaded • ${if (progress.bytesPerSecond > 0) Format.bytes(progress.bytesPerSecond) + "/s" else "—"} • ${etaText(progress.etaSeconds)} ETA",
                        style = MaterialTheme.typography.labelMedium,
                        color = Blue
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPause,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue)
                ) {
                    Icon(imageVector = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null)
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(if (paused) "Resume" else "Pause")
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = progress.currentFileId != null,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Red)
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = null)
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Cancel")
                }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(.8f).height(48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(imageVector = Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Stop")
                }
            }
            OutlinedButton(
                onClick = onDetails,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(15.dp)
            ) {
                Icon(imageVector = Icons.Default.List, contentDescription = null)
                Spacer(modifier = Modifier.width(7.dp))
                Text("View full backup details")
            }
        }
    }
}

@Composable
private fun CurrentFileCard(progress: UploadProgress, onOpen: () -> Unit) {
    val fraction = if (progress.currentFileBytes > 0L) {
        (progress.currentFileUploadedBytes.toDouble() / progress.currentFileBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    } else 0f
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        onClick = onOpen
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null, tint = Cyan, modifier = Modifier.size(30.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Current file", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        progress.currentFileName?.takeIf { it.isNotBlank() } ?: "Waiting for next file",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold
                    )
                }
                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null)
            }
            if (progress.currentFileBytes > 0L) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = Blue
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(Format.bytes(progress.currentFileUploadedBytes), style = MaterialTheme.typography.labelSmall, color = Blue)
                    Text(Format.bytes(progress.currentFileBytes), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, background: Color, tint: Color, modifier: Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = background)) {
        Column(modifier = Modifier.padding(11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DetailCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, background: Color, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = background), onClick = onClick) {
        Column(modifier = Modifier.padding(13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(25.dp))
            Spacer(modifier = Modifier.height(5.dp))
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ActionCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), onClick = onClick) {
        Row(modifier = Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = Blue, modifier = Modifier.size(25.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, tint: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold, color = tint)
    }
}

private fun etaText(seconds: Long): String = when {
    seconds <= 0L -> "—"
    seconds < 60L -> "${seconds}s"
    seconds < 3600L -> "${seconds / 60}m"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

private fun networkText(policy: NetworkPolicy, chargingOnly: Boolean): String = when {
    chargingOnly -> "charging"
    policy == NetworkPolicy.WIFI_ONLY -> "Wi-Fi"
    policy == NetworkPolicy.NOT_ROAMING -> "Wi-Fi/mobile without roaming"
    else -> "any connection"
}
