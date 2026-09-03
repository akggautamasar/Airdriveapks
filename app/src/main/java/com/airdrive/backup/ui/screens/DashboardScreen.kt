package com.airdrive.backup.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.CategoryTotals
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
    val enabledCategories by settings.enabledCategories.collectAsState(initial = BackupCategory.values().toSet())
    val progress by repository.progress.collectAsState()
    val paused by repository.paused.collectAsState()

    /** Badge for the menu: files that are only in Telegram now are worth a nudge, not a card. */
    val missingCount by remember { repository.missingCountFlow() }.collectAsState(initial = 0)

    /**
     * Same idea for cleanup: the menu carries the headline figure so "you can safely free 28.7 GB"
     * is visible without opening the screen. Summed here rather than in SQL because the same
     * per-category flow feeds the cleanup screen's own breakdown.
     */
    val cleanupTotals by remember { repository.cleanupTotalsFlow() }.collectAsState(initial = emptyList())
    val reclaimableBytes = remember(cleanupTotals) { cleanupTotals.sumOf { it.bytes } }

    /**
     * Verification problems are counted here rather than on the verify screen because the whole
     * point of the feature is to surface a backup that has quietly gone wrong; a number nobody
     * sees until they go looking is no better than not checking at all.
     */
    val verifyProblems by remember { repository.verifyProblemCountFlow() }.collectAsState(initial = 0)

    /** How many files have an older copy still reachable in Telegram. */
    val versionedFiles by remember { repository.versionedFileCountFlow() }.collectAsState(initial = 0)

    var hasAccess by remember { mutableStateOf(StorageAccess.hasFullAccess(context)) }

    /**
     * Why an automatic backup is not happening, if it is not happening. WorkManager holds a run
     * whose constraints are unmet without telling anyone, so a phone that has not been plugged in
     * for three days looks identical to a phone that is up to date. These three flags plus the
     * settings below turn that silence into a sentence. Re-read on resume rather than observed:
     * a charger is a thing the user does, so the answer only needs to be right when they look.
     */
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

    // Order matters: charging is checked first because it is the constraint people actually hit,
    // and naming two reasons at once helps nobody.
    val waitingFor: String? = when {
        !autoBackup || progress.isRunning -> null
        chargingOnly && !charging ->
            "Automatic backups are set to run only while charging, so nothing will upload until " +
                "the phone is plugged in."
        batteryConscious && batteryLow ->
            "Automatic backups are paused while the battery is low. They start again once there " +
                "is more charge."
        networkPolicy == NetworkPolicy.WIFI_ONLY && !unmetered ->
            "Automatic backups are set to Wi-Fi only, and this phone is on mobile data at the " +
                "moment."
        else -> null
    }

    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AirDrive") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Backup destination") }, onClick = {
                            menuOpen = false; nav.navigate(Routes.DESTINATION)
                        })
                        DropdownMenuItem(text = { Text("Channel configuration") }, onClick = {
                            menuOpen = false; nav.navigate(Routes.CHANNEL_CONFIG)
                        })
                        DropdownMenuItem(text = { Text("Storage access") }, onClick = {
                            menuOpen = false; nav.navigate(Routes.STORAGE_ACCESS)
                        })
                        DropdownMenuItem(text = { Text("Backup settings") }, onClick = {
                            menuOpen = false; nav.navigate(Routes.BACKUP_SETTINGS)
                        })
                        DropdownMenuItem(text = { Text("Search backups") }, onClick = {
                            menuOpen = false; nav.navigate(Routes.SEARCH)
                        })
                        DropdownMenuItem(text = { Text("Photo gallery") }, onClick = {
                            menuOpen = false; nav.navigate(Routes.GALLERY)
                        })
                        DropdownMenuItem(text = { Text("Backup timeline") }, onClick = {
                            menuOpen = false; nav.navigate(Routes.TIMELINE)
                        })
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (missingCount > 0) "Deleted files ($missingCount)"
                                    else "Deleted files"
                                )
                            },
                            onClick = { menuOpen = false; nav.navigate(Routes.DELETED_FILES) }
                        )
                        DropdownMenuItem(text = { Text("Restore from Telegram") }, onClick = {
                            menuOpen = false; nav.navigate(Routes.RESTORE)
                        })
                        DropdownMenuItem(text = { Text("Restore from old device") }, onClick = {
                            menuOpen = false; nav.navigate(Routes.MIGRATE)
                        })
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (reclaimableBytes > 0) {
                                        "Storage cleanup (${formatBytes(reclaimableBytes)})"
                                    } else {
                                        "Storage cleanup"
                                    }
                                )
                            },
                            onClick = { menuOpen = false; nav.navigate(Routes.CLEANUP) }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (verifyProblems > 0) "Backup verification ($verifyProblems)"
                                    else "Backup verification"
                                )
                            },
                            onClick = { menuOpen = false; nav.navigate(Routes.VERIFY) }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (versionedFiles > 0) "File history ($versionedFiles)"
                                    else "File history"
                                )
                            },
                            onClick = { menuOpen = false; nav.navigate(Routes.FILE_HISTORY) }
                        )
                        DropdownMenuItem(text = { Text("Failed uploads") }, onClick = {
                            menuOpen = false; nav.navigate(Routes.FAILED_UPLOADS)
                        })
                        DropdownMenuItem(text = { Text("About") }, onClick = {
                            menuOpen = false; nav.navigate(Routes.ABOUT)
                        })
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp)
        ) {
            Text("Backup Status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Last backup: ${formatLastBackup(lastBackup)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            if (!hasAccess) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Storage access is off", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "AirDrive can only see folders you picked by hand.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { nav.navigate(Routes.STORAGE_ACCESS) }) { Text("Fix this") }
                    }
                }
            }

            // Uploads cannot start until there is somewhere to put them, and a silent no-op is
            // exactly the failure people reported before this card existed.
            if (destination?.needsSetup == true) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("No destination yet", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Pick Saved Messages for zero setup, or point AirDrive at a channel.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { nav.navigate(Routes.DESTINATION) }) { Text("Choose") }
                    }
                }
            }

            // Not an error: the phone is following the rules it was given. The card exists so the
            // rule is visible, and so "BACK UP NOW still works" is said out loud — that button
            // deliberately ignores the charging rule, which is not obvious from the outside.
            waitingFor?.let { reason ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Automatic backup is waiting", style = MaterialTheme.typography.titleSmall)
                        Text(reason, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "BACK UP NOW is not affected — it runs whatever the phone is doing.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { nav.navigate(Routes.BACKUP_SETTINGS) }) {
                            Text("Backup settings")
                        }
                    }
                }
            }

            if (progress.isRunning) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${progress.doneFiles}/${progress.totalFiles} \u2022 ${progress.currentFileName ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { repository.setPaused(!paused) },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (paused) "Resume" else "Pause") }
                    OutlinedButton(
                        onClick = {
                            progress.currentFileId?.let { id -> scope.launch { repository.cancelUpload(id) } }
                        },
                        enabled = progress.currentFileId != null,
                        modifier = Modifier.weight(1f)
                    ) { Text("Cancel file") }
                    OutlinedButton(
                        onClick = { WorkScheduler.pauseManual(context); repository.setPaused(false) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Stop") }
                }
            } else {
                Button(
                    onClick = { repository.setPaused(false); WorkScheduler.runNow(context); nav.navigate(Routes.BACKUP_PROGRESS) },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("BACK UP NOW") }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                destinationSummary(destination?.mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatTile("Files backed up", uploadedCount.toString(), Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                StatTile("Storage uploaded", formatBytes(uploadedBytes), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatTile("Pending", pendingCount.toString(), Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                StatTile("Failed", failedCount.toString(), Modifier.weight(1f))
            }

            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Categories", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = { nav.navigate(Routes.CATEGORIES_STATS) }) { Text("View all") }
            }
            Spacer(Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f)
            ) {
                // Every category is listed, whether or not anything has uploaded yet, and the
                // counts include queued files: an empty grid told the user nothing.
                items(BackupCategory.values().toList()) { category ->
                    val row: CategoryTotals? = categoryTotals.find { it.category == category }
                    val enabled = category in enabledCategories
                    val categoryPending = (row?.total ?: 0) - (row?.uploaded ?: 0)
                    Card(modifier = Modifier.padding(6.dp).fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(categoryLabel(category), style = MaterialTheme.typography.titleMedium)
                                // Unchecking excludes this category from scans and from a plain
                                // "BACK UP NOW" — the per-category Upload button below still
                                // works regardless, since tapping it is an explicit choice.
                                Checkbox(
                                    checked = enabled,
                                    onCheckedChange = { checked ->
                                        val next = if (checked) enabledCategories + category else enabledCategories - category
                                        scope.launch { settings.setEnabledCategories(next) }
                                    },
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Text(
                                "${row?.uploaded ?: 0}/${row?.total ?: 0} files \u2022 " +
                                    formatBytes(row?.totalBytes ?: 0L),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    repository.setPaused(false)
                                    WorkScheduler.runNowCategory(context, category)
                                    nav.navigate(Routes.BACKUP_PROGRESS)
                                },
                                enabled = categoryPending > 0 && !progress.isRunning,
                                modifier = Modifier.fillMaxWidth().height(36.dp)
                            ) { Text(if (categoryPending > 0) "Upload ($categoryPending)" else "Up to date") }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { nav.navigate(Routes.ACTIVITY_HISTORY) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("View Activity") }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatLastBackup(millis: Long?): String {
    if (millis == null) return "Never"
    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return fmt.format(Date(millis))
}

private fun destinationSummary(mode: DestinationMode?): String = when (mode) {
    DestinationMode.SAVED_MESSAGES -> "Uploading to your Telegram Saved Messages"
    DestinationMode.SINGLE_CHAT -> "Uploading to one channel"
    DestinationMode.PER_CATEGORY -> "Uploading to a channel per file type"
    null -> ""
}
