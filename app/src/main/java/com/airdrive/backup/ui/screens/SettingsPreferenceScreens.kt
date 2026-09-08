package com.airdrive.backup.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.prefs.NetworkPolicy
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreferenceScaffold(title: String, subtitle: String, nav: NavHostController, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Column { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodySmall) } }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun SecurityPrivacyScreen(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val credentials by settings.apiCredentials.collectAsState(initial = null)
    PreferenceScaffold("Security & Privacy", "Only real privacy controls belong here", nav) {
        SettingsCard(Icons.Default.Security, "Telegram data", "AirDrive uploads to the Telegram destination you configure. Your files are not sent to an AirDrive storage server.")
        SettingsCard(Icons.Default.Key, "API credentials", if (credentials?.fromUser == true) "Custom Telegram API credentials are configured." else "Using the APK's configured Telegram API credentials.") { nav.navigate(Routes.API_CREDENTIALS) }
        SettingsCard(Icons.Default.CleaningServices, "Scan rules", "Folder exclusions, size limits, upload order and captions are backup controls.") { nav.navigate(Routes.ADVANCED_SETTINGS) }
        OutlinedButton(onClick = { scope.launch { settings.clearApiCredentials() } }, modifier = Modifier.fillMaxWidth()) { Text("Remove my custom API credentials") }
        Text("This does not delete files already uploaded to Telegram. It only removes credentials entered into this app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    PreferenceScaffold("Notifications", "Android controls notification permission and channels", nav) {
        SettingsCard(Icons.Default.NotificationsActive, "AirDrive notifications", "Open Android's notification settings to control AirDrive alerts, progress and sound.") {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply { putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName) })
        }
        SettingsCard(Icons.Default.NotificationsActive, "Why there is no duplicate switch", "Notification channels are managed by Android. AirDrive will not pretend a local switch controls a channel that Android owns.")
    }
}

@Composable
fun AppearanceSettingsScreen(nav: NavHostController) {
    val settings = remember { SettingsStore(LocalContext.current) }
    val scope = rememberCoroutineScope()
    val theme by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    PreferenceScaffold("Appearance", "Keep this page focused on visual preferences", nav) {
        ThemeRow("System default", ThemeMode.SYSTEM, theme, Icons.Default.DarkMode) { scope.launch { settings.setThemeMode(it) } }
        ThemeRow("Light", ThemeMode.LIGHT, theme, Icons.Default.LightMode) { scope.launch { settings.setThemeMode(it) } }
        ThemeRow("Dark", ThemeMode.DARK, theme, Icons.Default.DarkMode) { scope.launch { settings.setThemeMode(it) } }
        Text("Scan rules and captions are not appearance settings and are kept under Backup → Advanced settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun NetworkSettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val policy by settings.networkPolicy.collectAsState(initial = NetworkPolicy.WIFI_ONLY)
    PreferenceScaffold("Network", "Control when AirDrive may upload", nav) {
        NetworkRow("Wi-Fi only", "Safest for mobile data", NetworkPolicy.WIFI_ONLY, policy, Icons.Default.Wifi) { scope.launch { settings.setNetworkPolicy(it) } }
        NetworkRow("Wi-Fi + mobile, no roaming", "Use cellular data but avoid roaming", NetworkPolicy.NOT_ROAMING, policy, Icons.Default.Wifi) { scope.launch { settings.setNetworkPolicy(it) } }
        NetworkRow("Any connection", "Wi-Fi, mobile data and roaming", NetworkPolicy.ANY, policy, Icons.Default.WifiOff) { scope.launch { settings.setNetworkPolicy(it) } }
        Text("These settings affect automatic backup uploads. They do not change Telegram's own network behaviour.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { nav.navigate(Routes.BACKUP_SETTINGS) }) { Text("Open full Backup settings") }
    }
}

@Composable private fun SettingsCard(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    Card(modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier), shape = RoundedCornerShape(18.dp)) { Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
}

@Composable private fun ThemeRow(label: String, mode: ThemeMode, selected: ThemeMode, icon: ImageVector, onPick: (ThemeMode) -> Unit) { Card(Modifier.fillMaxWidth().clickable { onPick(mode) }, shape = RoundedCornerShape(16.dp)) { Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, modifier = Modifier.padding(8.dp)); Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold); RadioButton(mode == selected, { onPick(mode) }) } } }

@Composable private fun NetworkRow(label: String, description: String, policy: NetworkPolicy, selected: NetworkPolicy, icon: ImageVector, onPick: (NetworkPolicy) -> Unit) { Card(Modifier.fillMaxWidth().clickable { onPick(policy) }, shape = RoundedCornerShape(16.dp)) { Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, modifier = Modifier.padding(8.dp)); Column(Modifier.weight(1f)) { Text(label, fontWeight = FontWeight.SemiBold); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; RadioButton(policy == selected, { onPick(policy) }) } } }
