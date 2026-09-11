package com.airdrive.backup.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.ui.nav.Routes

private const val QUANTX_ALLOWED_PHONE = "+916307868952"
private val Blue = Color(0xFF2F6FEA)
private val BlueLight = Color(0xFFEAF2FF)
private val Green = Color(0xFF20A463)
private val GreenLight = Color(0xFFE5F7EE)
private val Purple = Color(0xFF7C3AED)
private val PurpleLight = Color(0xFFF0E8FF)
private val Orange = Color(0xFFF59E0B)
private val OrangeLight = Color(0xFFFFF4DD)
private val Cyan = Color(0xFF18B8C8)
private val CyanLight = Color(0xFFE2F8FA)
private val Background = Color(0xFFF7F9FD)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsOverviewScreen(nav: NavHostController) {
    val context = LocalContext.current
    val identity = remember { context.getSharedPreferences("quantxdrive_identity", 0).getString("phone", "") ?: "" }
    val quantEnabled = identity.filter { !it.isWhitespace() }.replace("-", "") == QUANTX_ALLOWED_PHONE
    Scaffold(containerColor = Background, topBar = {
        TopAppBar(title = { Text("Settings", fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Background))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = BlueLight)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(62.dp).clip(RoundedCornerShape(18.dp)).background(Blue), contentAlignment = Alignment.Center) { Icon(Icons.Default.Cloud, null, tint = Color.White, modifier = Modifier.size(37.dp)) }
                    Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) {
                        Text("AirDrive", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF15356F))
                        Text("v1.0.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(5.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(7.dp).clip(CircleShape).background(Green)); Spacer(Modifier.width(6.dp)); Text("Backup system ready", style = MaterialTheme.typography.labelMedium, color = Color(0xFF176B47), fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            SettingsGroup("Backup") {
                SettingsRow("Backup", "Automatic backup, schedule and file types", Icons.Default.Folder, Blue, BlueLight) { nav.navigate(Routes.BACKUP_SETTINGS) }
                SettingsRow("Storage", "Permissions and scan locations", Icons.Default.Folder, Green, GreenLight) { nav.navigate(Routes.STORAGE_ACCESS) }
                SettingsRow("Telegram", "Connection and backup destination", Icons.Default.Send, Blue, BlueLight) { nav.navigate(Routes.TELEGRAM_SETTINGS) }
            }
            if (quantEnabled) {
                SettingsGroup("Private tools") {
                    SettingsRow("QuantxDrive", "Private cloud storage for your authorized account", Icons.Default.Cloud, Purple, PurpleLight) { nav.navigate(Routes.QUANTXDRIVE) }
                }
            }
            SettingsGroup("Preferences") {
                SettingsRow("Security & Privacy", "Credentials, cloud data and privacy", Icons.Default.Lock, Orange, OrangeLight) { nav.navigate(Routes.SECURITY_PRIVACY) }
                SettingsRow("App Lock", "Optional biometric or device PIN protection", Icons.Default.Lock, Orange, OrangeLight) { nav.navigate(Routes.APP_LOCK) }
                SettingsRow("Notifications", "Backup progress and failure alerts", Icons.Default.Notifications, Purple, PurpleLight) { nav.navigate(Routes.NOTIFICATIONS) }
                SettingsRow("Appearance", "Theme", Icons.Default.Palette, Purple, PurpleLight) { nav.navigate(Routes.APPEARANCE) }
                SettingsRow("Network", "Wi-Fi, mobile data and roaming", Icons.Default.Wifi, Cyan, CyanLight) { nav.navigate(Routes.NETWORK) }
            }
            SettingsGroup("Support") { SettingsRow("About AirDrive", "Version, help, privacy and open source", Icons.Default.Info, Blue, BlueLight) { nav.navigate(Routes.ABOUT) } }
            Text("AirDrive • Your files. Backed up. Always with you.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 2.dp)); Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(content = content) } } }
@Composable private fun SettingsRow(title: String, subtitle: String, icon: ImageVector, accent: Color, iconBackground: Color, onClick: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(iconBackground), contentAlignment = Alignment.Center) { Icon(icon, null, tint = accent, modifier = Modifier.size(23.dp)) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }; Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
