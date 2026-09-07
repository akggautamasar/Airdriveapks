package com.airdrive.backup.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

private val Blue = Color(0xFF2F6FEA)
private val BlueLight = Color(0xFFEAF2FF)
private val Green = Color(0xFF20A463)
private val GreenLight = Color(0xFFE5F7EE)
private val Orange = Color(0xFFF59E0B)
private val OrangeLight = Color(0xFFFFF4DD)
private val Purple = Color(0xFF7C3AED)
private val PurpleLight = Color(0xFFF0E8FF)
private val Red = Color(0xFFE5484D)
private val RedLight = Color(0xFFFFE8E8)
private val Cyan = Color(0xFF18B8C8)
private val CyanLight = Color(0xFFE2F8FA)
private val Page = Color(0xFFF7F9FD)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()
    var manifestStatus by remember { mutableStateOf<String?>(null) }
    var manifestBusy by remember { mutableStateOf(false) }
    var networkDialog by remember { mutableStateOf(false) }
    var scheduleDialog by remember { mutableStateOf(false) }
    var themeDialog by remember { mutableStateOf(false) }
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
        containerColor = Page,
        topBar = {
            TopAppBar(
                title = { Text("Backup Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Page)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingHero(autoBackup)
            SectionLabel("Automatic backup")
            ModernSettingCard {
                SettingSwitchRow(Icons.Default.SettingsBackupRestore, Blue, BlueLight, "Automatic backup", "Keep your files safe automatically", autoBackup) { scope.launch { settings.setAutoBackupEnabled(it) }; reschedule() }
                Divider()
                SettingLinkRow(Icons.Default.NetworkWifi, Blue, BlueLight, "Upload over", networkLabel(networkPolicy)) { networkDialog = true }
                SettingSwitchRow(Icons.Default.BatteryChargingFull, Green, GreenLight, "Charging only", "Only back up while charging", chargingOnly) { scope.launch { settings.setChargingOnly(it) }; reschedule() }
                SettingSwitchRow(Icons.Default.Lightbulb, Green, GreenLight, "Battery-conscious mode", "Pause backup when battery is low", batteryConscious) { scope.launch { settings.setBatteryConscious(it) }; reschedule() }
                SettingSwitchRow(Icons.Default.Refresh, Orange, OrangeLight, "Retry failed files", "Automatically retry failed uploads", autoRetry) { scope.launch { settings.setAutoRetryFailed(it) } }
            }
            SectionLabel("Schedule & limits")
            ModernSettingCard {
                SettingLinkRow(Icons.Default.Schedule, Blue, BlueLight, "Backup schedule", "Every ${frequency} hours") { scheduleDialog = true }
                SettingLinkRow(Icons.Default.Bolt, Purple, PurpleLight, "Small files", if (includeSmall) "Included" else "Excluded") { scope.launch { settings.setIncludeSmallFiles(!includeSmall) } }
                Slider(value = frequency.toFloat(), onValueChange = { scope.launch { settings.setBackupFrequencyHours(it.toLong().coerceIn(1L, 24L)) } }, onValueChangeFinished = { reschedule() }, valueRange = 1f..24f, steps = 22, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp), colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue))
            }
            SectionLabel("Storage")
            ModernSettingCard {
                SettingSwitchRow(Icons.Default.Storage, Cyan, CyanLight, "Scan every folder", "Find files across your device", wholeDevice) { scope.launch { settings.setScanWholeDevice(it) } }
                SettingSwitchRow(Icons.Default.Folder, Green, GreenLight, "Include SD card / USB", "Scan removable storage too", includeSdCard) { scope.launch { settings.setIncludeSdCard(it) } }
                Divider()
                Text(if (hasAccess) StorageAccess.describeRoots(context, includeSdCard) else "All files access is off — only selected folders can be scanned.", style = MaterialTheme.typography.bodySmall, color = if (hasAccess) MaterialTheme.colorScheme.onSurfaceVariant else Red, modifier = Modifier.padding(14.dp, 10.dp))
                TextButton(onClick = { nav.navigate(Routes.STORAGE_ACCESS) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Manage storage access", color = Blue) }
            }
            SectionLabel("Include file types")
            ModernSettingCard {
                for (category in BackupCategory.values()) {
                    val enabled = category in enabledCategories
                    CategoryToggle(category, enabled) { checked ->
                        val next = if (checked) enabledCategories + category else enabledCategories - category
                        scope.launch { settings.setEnabledCategories(next) }
                    }
                }
            }
            SectionLabel("Backup data on Telegram")
            ModernSettingCard {
                SettingLinkRow(Icons.Default.CloudUpload, Blue, BlueLight, "Manifest backup", "Keeps backup history on Telegram") { }
                Text("AirDrive stores a manifest in your own Saved Messages so files already backed up can be recognised after reinstall or device migration.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        manifestBusy = true
                        manifestStatus = null
                        scope.launch {
                            val ok = repository.syncManifestNow()
                            manifestStatus = if (ok) "Synced to Telegram." else "Sync failed — check Telegram sign-in."
                            manifestBusy = false
                        }
                    }, enabled = !manifestBusy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) { Text("Sync now") }
                    OutlinedButton(onClick = {
                        manifestBusy = true
                        manifestStatus = null
                        scope.launch {
                            manifestStatus = when (val r = repository.restoreManifestForced()) {
                                is ManifestSync.RestoreResult.Restored -> "Restored ${r.fileCount} file(s)."
                                ManifestSync.RestoreResult.NoManifestFound -> "No backup data found."
                                ManifestSync.RestoreResult.NotSignedIn -> "Not signed in to Telegram."
                                ManifestSync.RestoreResult.NothingToDo -> "Nothing to restore."
                                is ManifestSync.RestoreResult.Failed -> "Restore failed: ${r.reason}"
                            }
                            manifestBusy = false
                        }
                    }, enabled = !manifestBusy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) { Text("Restore now") }
                }
                if (manifestBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), color = Blue)
                manifestStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp)) }
            }
            SectionLabel("Smart Backup")
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = BlueLight), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingIcon(Icons.Default.Lightbulb, Orange, OrangeLight)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Smart Backup", fontWeight = FontWeight.Bold, color = Color(0xFF174A9C))
                        Text("Back up new or changed files first to save time and data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SectionLabel("More")
            ModernSettingCard {
                SettingLinkRow(Icons.Default.Storage, Blue, BlueLight, "Backup destination", "Saved Messages or channel") { nav.navigate(Routes.DESTINATION) }
                SettingLinkRow(Icons.Default.Security, Purple, PurpleLight, "Advanced settings", "Scan rules, captions and export") { nav.navigate(Routes.ADVANCED_SETTINGS) }
                SettingLinkRow(Icons.Default.History, Cyan, CyanLight, "Restore files", "Restore from Telegram") { nav.navigate(Routes.RESTORE) }
            }
            ModernSettingCard {
                SettingLinkRow(Icons.Default.CheckCircle, Green, GreenLight, "Appearance", themeLabel(themeMode)) { themeDialog = true }
                SettingLinkRow(Icons.Default.Description, Blue, BlueLight, "Telegram API keys", "Manage Telegram credentials") { nav.navigate(Routes.API_CREDENTIALS) }
            }
            OutlinedButton(onClick = { WorkScheduler.runNow(context) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Run backup now") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (networkDialog) {
        AlertDialog(onDismissRequest = { networkDialog = false }, title = { Text("Upload over") }, text = { Column {
            NetworkChoice("Wi-Fi only", NetworkPolicy.WIFI_ONLY, networkPolicy) { scope.launch { settings.setNetworkPolicy(it) }; reschedule(); networkDialog = false }
            NetworkChoice("Wi-Fi or mobile data, not roaming", NetworkPolicy.NOT_ROAMING, networkPolicy) { scope.launch { settings.setNetworkPolicy(it) }; reschedule(); networkDialog = false }
            NetworkChoice("Any connection", NetworkPolicy.ANY, networkPolicy) { scope.launch { settings.setNetworkPolicy(it) }; reschedule(); networkDialog = false }
        } }, confirmButton = {})
    }
    if (scheduleDialog) {
        AlertDialog(onDismissRequest = { scheduleDialog = false }, title = { Text("Backup schedule") }, text = { Column {
            listOf(1L, 2L, 6L, 12L, 24L).forEach { hours ->
                Row(Modifier.fillMaxWidth().clickable { scope.launch { settings.setBackupFrequencyHours(hours) }; reschedule(); scheduleDialog = false }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(hours == frequency, null)
                    Text("Every ${hours} hours", Modifier.padding(start = 8.dp))
                }
            }
        } }, confirmButton = {})
    }
    if (themeDialog) {
        AlertDialog(onDismissRequest = { themeDialog = false }, title = { Text("Theme") }, text = { Column {
            ThemeChoice("System", ThemeMode.SYSTEM, themeMode) { scope.launch { settings.setThemeMode(it) }; themeDialog = false }
            ThemeChoice("Light", ThemeMode.LIGHT, themeMode) { scope.launch { settings.setThemeMode(it) }; themeDialog = false }
            ThemeChoice("Dark", ThemeMode.DARK, themeMode) { scope.launch { settings.setThemeMode(it) }; themeDialog = false }
        } }, confirmButton = {})
    }
}

@Composable
private fun SettingHero(enabled: Boolean) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = BlueLight), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingIcon(Icons.Default.SettingsBackupRestore, Blue, Color(0xFFD6E7FF))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Backup Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF174A9C))
                Text(if (enabled) "Automatic backup is enabled" else "Automatic backup is paused", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(10.dp).background(if (enabled) Green else Orange, RoundedCornerShape(50)))
        }
    }
}

@Composable private fun SectionLabel(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 2.dp, top = 4.dp)) }
@Composable private fun ModernSettingCard(content: @Composable ColumnScope.() -> Unit) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(content = content) } }
@Composable private fun SettingIcon(icon: ImageVector, tint: Color, bg: Color) { Box(Modifier.size(44.dp).background(bg, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(23.dp)) } }
@Composable private fun SettingSwitchRow(icon: ImageVector, tint: Color, bg: Color, title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { SettingIcon(icon, tint, bg); Spacer(Modifier.width(11.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = checked, onCheckedChange = onToggle) } }
@Composable private fun SettingLinkRow(icon: ImageVector, tint: Color, bg: Color, title: String, subtitle: String, onClick: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) { SettingIcon(icon, tint, bg); Spacer(Modifier.width(11.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun CategoryToggle(category: BackupCategory, checked: Boolean, onToggle: (Boolean) -> Unit) { val (tint, bg) = categoryColors(category); Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { SettingIcon(Icons.Default.Description, tint, bg); Spacer(Modifier.width(10.dp)); Text(categoryLabel(category), Modifier.weight(1f), fontWeight = FontWeight.Medium); Checkbox(checked = checked, onCheckedChange = onToggle) } }
private fun categoryColors(c: BackupCategory): Pair<Color, Color> = when (c) { BackupCategory.PHOTOS -> Blue to BlueLight; BackupCategory.VIDEOS -> Orange to OrangeLight; BackupCategory.PDFS -> Red to RedLight; BackupCategory.WORD_EXCEL -> Cyan to CyanLight; BackupCategory.AUDIO -> Purple to PurpleLight; BackupCategory.CALL_RECORDINGS -> Color(0xFF18B89C) to Color(0xFFE2F8F1); BackupCategory.OTHER_FILES -> Color(0xFF64748B) to Color(0xFFEFF2F6) }
private fun networkLabel(p: NetworkPolicy) = when (p) { NetworkPolicy.WIFI_ONLY -> "Wi-Fi only"; NetworkPolicy.NOT_ROAMING -> "Wi-Fi or mobile data"; NetworkPolicy.ANY -> "Any connection" }
private fun themeLabel(t: ThemeMode) = when (t) { ThemeMode.SYSTEM -> "Follow system"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" }
@Composable private fun NetworkChoice(label: String, option: NetworkPolicy, selected: NetworkPolicy, onPick: (NetworkPolicy) -> Unit) { Row(Modifier.fillMaxWidth().clickable { onPick(option) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(option == selected, { onPick(option) }); Text(label, Modifier.padding(start = 4.dp)) } }
@Composable private fun ThemeChoice(label: String, option: ThemeMode, selected: ThemeMode, onPick: (ThemeMode) -> Unit) { Row(Modifier.fillMaxWidth().clickable { onPick(option) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(option == selected, { onPick(option) }); Text(label, Modifier.padding(start = 4.dp)) } }
