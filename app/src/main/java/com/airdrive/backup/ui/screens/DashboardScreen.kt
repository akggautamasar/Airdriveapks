package com.airdrive.backup.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(nav: NavHostController) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val settings = remember { SettingsStore(context) }
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()
    val uploadedCount by db.fileRecordDao().uploadedCountFlow().collectAsState(initial = 0)
    val pendingCount by db.fileRecordDao().pendingCountFlow().collectAsState(initial = 0)
    val failedCount by db.fileRecordDao().failedCountFlow().collectAsState(initial = 0)
    val uploadedBytes by db.fileRecordDao().uploadedBytesFlow().collectAsState(initial = 0L)
    val lastBackup by db.fileRecordDao().lastBackupTimeFlow().collectAsState(initial = null)
    val categoryTotals by db.fileRecordDao().categoryTotalsFlow().collectAsState(initial = emptyList())
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

    OnResumeEffect {
        hasAccess = StorageAccess.hasFullAccess(context)
        charging = DeviceState.isCharging(context)
        batteryLow = DeviceState.isBatteryLow(context)
        unmetered = DeviceState.isUnmetered(context)
    }

    val waitingFor: String? = when {
        !autoBackup || progress.isRunning -> null
        chargingOnly && !charging -> "Automatic backups are set to run only while charging."
        batteryConscious && batteryLow -> "Automatic backups are paused while the battery is low."
        networkPolicy == NetworkPolicy.WIFI_ONLY && !unmetered -> "Automatic backups are waiting for Wi-Fi."
        else -> null
    }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AirDrive", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem({ Text("Backup destination") }, { menuOpen = false; nav.navigate(Routes.DESTINATION) })
                        DropdownMenuItem({ Text("Channel configuration") }, { menuOpen = false; nav.navigate(Routes.CHANNEL_CONFIG) })
                        DropdownMenuItem({ Text("Storage access") }, { menuOpen = false; nav.navigate(Routes.STORAGE_ACCESS) })
                        DropdownMenuItem({ Text("Backup settings") }, { menuOpen = false; nav.navigate(Routes.BACKUP_SETTINGS) })
                        DropdownMenuItem({ Text("Search backups") }, { menuOpen = false; nav.navigate(Routes.SEARCH) })
                        DropdownMenuItem({ Text("Photo gallery") }, { menuOpen = false; nav.navigate(Routes.GALLERY) })
                        DropdownMenuItem({ Text("Backup timeline") }, { menuOpen = false; nav.navigate(Routes.TIMELINE) })
                        DropdownMenuItem({ Text(if (missingCount > 0) "Deleted files ($missingCount)" else "Deleted files") }, { menuOpen = false; nav.navigate(Routes.DELETED_FILES) })
                        DropdownMenuItem({ Text("Restore from Telegram") }, { menuOpen = false; nav.navigate(Routes.RESTORE) })
                        DropdownMenuItem({ Text("Restore from old device") }, { menuOpen = false; nav.navigate(Routes.MIGRATE) })
                        DropdownMenuItem({ Text(if (reclaimableBytes > 0) "Storage cleanup (${formatBytes(reclaimableBytes)})" else "Storage cleanup") }, { menuOpen = false; nav.navigate(Routes.CLEANUP) })
                        DropdownMenuItem({ Text(if (verifyProblems > 0) "Backup verification ($verifyProblems)" else "Backup verification") }, { menuOpen = false; nav.navigate(Routes.VERIFY) })
                        DropdownMenuItem({ Text(if (versionedFiles > 0) "File history ($versionedFiles)" else "File history") }, { menuOpen = false; nav.navigate(Routes.FILE_HISTORY) })
                        DropdownMenuItem({ Text("Failed uploads") }, { menuOpen = false; nav.navigate(Routes.FAILED_UPLOADS) })
                        DropdownMenuItem({ Text("About") }, { menuOpen = false; nav.navigate(Routes.ABOUT) })
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 34.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Text("Backup Status", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
            Text("Last backup: ${formatLastBackup(lastBackup)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(22.dp))

            if (!hasAccess) {
                NoticeCard("Storage access is off", "Allow AirDrive to scan your files.", "Fix this") { nav.navigate(Routes.STORAGE_ACCESS) }
                Spacer(Modifier.height(10.dp))
            }
            if (destination?.needsSetup == true) {
                NoticeCard("No destination yet", "Choose Saved Messages or a channel.", "Choose") { nav.navigate(Routes.DESTINATION) }
                Spacer(Modifier.height(10.dp))
            }
            waitingFor?.let {
                NoticeCard("Automatic backup is waiting", it, "Settings") { nav.navigate(Routes.BACKUP_SETTINGS) }
                Spacer(Modifier.height(10.dp))
            }

            if (progress.isRunning) {
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth().height(7.dp))
                Spacer(Modifier.height(7.dp))
                Text("${progress.doneFiles}/${progress.totalFiles} • ${progress.currentFileName ?: "Uploading files…"}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { repository.setPaused(!paused) }, modifier = Modifier.weight(1f)) { Text(if (paused) "Resume" else "Pause") }
                    OutlinedButton(onClick = { progress.currentFileId?.let { id -> scope.launch { repository.cancelUpload(id) } } }, enabled = progress.currentFileId != null, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    OutlinedButton(onClick = { WorkScheduler.pauseManual(context); repository.setPaused(false) }, modifier = Modifier.weight(1f)) { Text("Stop") }
                }
            } else {
                Button(
                    onClick = { repository.setPaused(false); WorkScheduler.runNow(context); nav.navigate(Routes.BACKUP_PROGRESS) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(30.dp)
                ) { Text("BACK UP NOW", fontWeight = FontWeight.Medium) }
            }
            Spacer(Modifier.height(14.dp))
            Text(destinationSummary(destination?.mode), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile(uploadedCount.toString(), "Files backed up", Modifier.weight(1f))
                StatTile(formatBytes(uploadedBytes), "Storage uploaded", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile(pendingCount.toString(), "Pending", Modifier.weight(1f))
                StatTile(failedCount.toString(), "Failed", Modifier.weight(1f))
            }

            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Categories", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                TextButton(onClick = { nav.navigate(Routes.CATEGORIES_STATS) }) { Text("View all") }
            }
            Spacer(Modifier.height(4.dp))

            LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(BackupCategory.values().toList()) { category ->
                    val row: CategoryTotals? = categoryTotals.find { it.category == category }
                    val pending = (row?.total ?: 0) - (row?.uploaded ?: 0)
                    Card(
                        modifier = Modifier.padding(6.dp).fillMaxWidth().clickable { nav.navigate("${Routes.CATEGORY_DETAIL}/${category.name}") },
                        shape = RoundedCornerShape(17.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(15.dp)) {
                            Text(categoryLabel(category), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text("${row?.uploaded ?: 0}/${row?.total ?: 0} files • ${formatBytes(row?.totalBytes ?: 0L)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = { repository.setPaused(false); WorkScheduler.runNowCategory(context, category); nav.navigate(Routes.BACKUP_PROGRESS) },
                                enabled = pending > 0 && !progress.isRunning,
                                modifier = Modifier.fillMaxWidth().height(38.dp),
                                shape = RoundedCornerShape(20.dp)
                            ) { Text(if (pending > 0) "Upload ($pending)" else "Up to date") }
                        }
                    }
                }
            }

            OutlinedButton(onClick = { nav.navigate(Routes.ACTIVITY_HISTORY) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(24.dp)) {
                Text("View Activity")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun NoticeCard(title: String, message: String, action: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatLastBackup(millis: Long?): String {
    if (millis == null) return "Never"
    return SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(millis))
}

private fun destinationSummary(mode: DestinationMode?): String = when (mode) {
    DestinationMode.SAVED_MESSAGES -> "Uploading to your Telegram Saved Messages"
    DestinationMode.SINGLE_CHAT -> "Uploading to one channel"
    DestinationMode.PER_CATEGORY -> "Uploading to a channel per file type"
    null -> ""
}
