package com.airdrive.backup.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.backup.ManifestSync
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.prefs.NetworkPolicy
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.ui.theme.ThemeMode
import com.airdrive.backup.util.StorageAccess
import com.airdrive.backup.work.WorkScheduler
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()
    var manifestStatus by remember { mutableStateOf<String?>(null) }
    var manifestBusy by remember { mutableStateOf(false) }

    val autoBackup by settings.autoBackupEnabled.collectAsState(initial = true)
    val networkPolicy by settings.networkPolicy.collectAsState(initial = NetworkPolicy.WIFI_ONLY)
    val chargingOnly by settings.chargingOnly.collectAsState(initial = false)
    val batteryConscious by settings.batteryConscious.collectAsState(initial = true)
    val includeSmall by settings.includeSmallFiles.collectAsState(initial = false)
    val frequency by settings.backupFrequencyHours.collectAsState(initial = 6L)
    val wholeDevice by settings.scanWholeDevice.collectAsState(initial = true)
    val includeSdCard by settings.includeSdCard.collectAsState(initial = true)
    val autoRetry by settings.autoRetryFailed.collectAsState(initial = true)
    val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val enabledCategories by settings.enabledCategories.collectAsState(initial = BackupCategory.values().toSet())

    var hasAccess by remember { mutableStateOf(StorageAccess.hasFullAccess(context)) }
    OnResumeEffect { hasAccess = StorageAccess.hasFullAccess(context) }

    fun reschedule() = scope.launch { WorkScheduler.rescheduleAutoBackup(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup Settings") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SettingRow("Automatic backup", autoBackup) {
                scope.launch { settings.setAutoBackupEnabled(it) }; reschedule()
            }

            // One three-way choice instead of the old "Wi-Fi only" + "Allow mobile data" pair,
            // which could be switched on together and then contradicted itself.
            Spacer(Modifier.height(8.dp))
            Text("Upload over", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.padding(top = 4.dp)) {
                NetworkPolicyOption("Wi-Fi only", NetworkPolicy.WIFI_ONLY, networkPolicy) {
                    scope.launch { settings.setNetworkPolicy(it) }; reschedule()
                }
                NetworkPolicyOption(
                    "Wi-Fi or mobile data, but not roaming",
                    NetworkPolicy.NOT_ROAMING,
                    networkPolicy
                ) {
                    scope.launch { settings.setNetworkPolicy(it) }; reschedule()
                }
                NetworkPolicyOption("Any connection", NetworkPolicy.ANY, networkPolicy) {
                    scope.launch { settings.setNetworkPolicy(it) }; reschedule()
                }
            }
            Text(
                "Applies to “Back up now” too, not just scheduled runs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
            SettingRow("Charging only", chargingOnly) {
                scope.launch { settings.setChargingOnly(it) }; reschedule()
            }
            SettingRow("Battery-conscious mode", batteryConscious) {
                scope.launch { settings.setBatteryConscious(it) }; reschedule()
            }
            SettingRow("Include files under 1 KB", includeSmall) {
                scope.launch { settings.setIncludeSmallFiles(it) }
            }
            SettingRow("Retry failed files automatically", autoRetry) {
                scope.launch { settings.setAutoRetryFailed(it) }
            }

            Spacer(Modifier.height(8.dp))
            Text("Storage", style = MaterialTheme.typography.titleMedium)
            SettingRow("Scan every folder on the phone", wholeDevice) {
                scope.launch { settings.setScanWholeDevice(it) }
            }
            SettingRow("Include SD card / USB storage", includeSdCard) {
                scope.launch { settings.setIncludeSdCard(it) }
            }
            Text(
                if (hasAccess) StorageAccess.describeRoots(context, includeSdCard)
                else "All files access is off, so only hand-picked folders are scanned.",
                style = MaterialTheme.typography.bodySmall,
                color = if (hasAccess) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error
            )
            TextButton(onClick = { nav.navigate(Routes.STORAGE_ACCESS) }) {
                Text("Manage storage access")
            }

            Spacer(Modifier.height(16.dp))
            Text("Backup frequency: every ${frequency}h", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = frequency.toFloat(),
                onValueChange = { scope.launch { settings.setBackupFrequencyHours(it.toLong()) } },
                onValueChangeFinished = { reschedule() },
                valueRange = 1f..24f,
                steps = 22
            )

            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.height(16.dp))
            Text("Categories", style = MaterialTheme.typography.titleMedium)
            Text(
                "Unchecking a category excludes it from scans and from a plain \"Back up now\" run. " +
                    "The per-category Upload button on the dashboard still works regardless — " +
                    "tapping it is always an explicit choice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.padding(top = 4.dp)) {
                for (category in BackupCategory.values()) {
                    val enabled = category in enabledCategories
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = enabled,
                            onCheckedChange = { checked ->
                                val next = if (checked) enabledCategories + category else enabledCategories - category
                                scope.launch { settings.setEnabledCategories(next) }
                            }
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(categoryLabel(category), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.padding(top = 4.dp)) {
                ThemeModeOption("Follow system", ThemeMode.SYSTEM, themeMode) {
                    scope.launch { settings.setThemeMode(it) }
                }
                ThemeModeOption("Light", ThemeMode.LIGHT, themeMode) {
                    scope.launch { settings.setThemeMode(it) }
                }
                ThemeModeOption("Dark", ThemeMode.DARK, themeMode) {
                    scope.launch { settings.setThemeMode(it) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Backup data on Telegram", style = MaterialTheme.typography.titleMedium)
            Text(
                "AirDrive keeps a list of everything already backed up inside your own Saved " +
                    "Messages (pinned, marked “DO NOT DELETE”). If you ever reinstall AirDrive, " +
                    "it reads this back automatically so already-backed-up files are recognised " +
                    "and skipped instead of re-uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        manifestBusy = true
                        manifestStatus = null
                        scope.launch {
                            val ok = repository.syncManifestNow()
                            manifestStatus = if (ok) "Synced to Telegram." else "Sync failed — check you're signed in."
                            manifestBusy = false
                        }
                    },
                    enabled = !manifestBusy,
                    modifier = Modifier.weight(1f)
                ) { Text("Sync now") }
                OutlinedButton(
                    onClick = {
                        manifestBusy = true
                        manifestStatus = null
                        scope.launch {
                            manifestStatus = when (val r = repository.restoreManifestForced()) {
                                is ManifestSync.RestoreResult.Restored ->
                                    "Restored ${r.fileCount} previously backed-up file(s)."
                                ManifestSync.RestoreResult.NoManifestFound -> "No backup data found on this account yet."
                                ManifestSync.RestoreResult.NotSignedIn -> "Not signed in to Telegram yet."
                                ManifestSync.RestoreResult.NothingToDo -> "Nothing to restore."
                                is ManifestSync.RestoreResult.Failed -> "Restore failed: ${r.reason}"
                            }
                            manifestBusy = false
                        }
                    },
                    enabled = !manifestBusy,
                    modifier = Modifier.weight(1f)
                ) { Text("Restore now") }
            }
            if (manifestBusy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            manifestStatus?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))
            Text("More", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { nav.navigate(Routes.DESTINATION) }) {
                Text("Backup destination")
            }
            TextButton(onClick = { nav.navigate(Routes.ADVANCED_SETTINGS) }) {
                Text("Scan rules, captions and export")
            }
            TextButton(onClick = { nav.navigate(Routes.RESTORE) }) {
                Text("Restore files from Telegram")
            }
            TextButton(onClick = { nav.navigate(Routes.API_CREDENTIALS) }) {
                Text("Telegram API keys")
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { WorkScheduler.runNow(context) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Retry failed uploads on next run") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ThemeModeOption(
    label: String,
    option: ThemeMode,
    selected: ThemeMode,
    onPick: (ThemeMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = option == selected, onClick = { onPick(option) })
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun NetworkPolicyOption(
    label: String,
    option: NetworkPolicy,
    selected: NetworkPolicy,
    onPick: (NetworkPolicy) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = option == selected, onClick = { onPick(option) })
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}
