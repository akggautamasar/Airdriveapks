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
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.StorageAccess
import com.airdrive.backup.work.WorkScheduler
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    val autoBackup by settings.autoBackupEnabled.collectAsState(initial = true)
    val wifiOnly by settings.wifiOnly.collectAsState(initial = true)
    val chargingOnly by settings.chargingOnly.collectAsState(initial = false)
    val allowMobile by settings.allowMobileData.collectAsState(initial = false)
    val batteryConscious by settings.batteryConscious.collectAsState(initial = true)
    val includeSmall by settings.includeSmallFiles.collectAsState(initial = false)
    val frequency by settings.backupFrequencyHours.collectAsState(initial = 6L)
    val wholeDevice by settings.scanWholeDevice.collectAsState(initial = true)
    val includeSdCard by settings.includeSdCard.collectAsState(initial = true)

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
            SettingRow("Wi-Fi only", wifiOnly) {
                scope.launch { settings.setWifiOnly(it) }; reschedule()
            }
            SettingRow("Allow mobile data", allowMobile) {
                scope.launch { settings.setAllowMobileData(it) }; reschedule()
            }
            SettingRow("Charging only", chargingOnly) {
                scope.launch { settings.setChargingOnly(it) }; reschedule()
            }
            SettingRow("Battery-conscious mode", batteryConscious) {
                scope.launch { settings.setBatteryConscious(it) }; reschedule()
            }
            SettingRow("Include files under 1 KB", includeSmall) {
                scope.launch { settings.setIncludeSmallFiles(it) }
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
            OutlinedButton(
                onClick = { WorkScheduler.runNow(context) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Retry failed uploads on next run") }
        }
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
