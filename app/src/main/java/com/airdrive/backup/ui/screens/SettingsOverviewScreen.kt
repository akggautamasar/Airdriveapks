package com.airdrive.backup.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.ui.nav.Routes

private val Blue = Color(0xFF2F6FEA)
private val BlueLight = Color(0xFFEAF2FF)
private val Green = Color(0xFF20A463)
private val GreenLight = Color(0xFFE5F7EE)
private val Purple = Color(0xFF7C3AED)
private val PurpleLight = Color(0xFFF0E8FF)
private val Orange = Color(0xFFF59E0B)
private val OrangeLight = Color(0xFFFFF4DD)
private val Background = Color(0xFFF7F9FD)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsOverviewScreen(nav: NavHostController) {
    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(Blue), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Cloud, null, tint = Color.White, modifier = Modifier.size(39.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("AirDrive", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("v1.0.0 • Build 46", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text("Your files. Backed up. Always with you.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            SettingsGroup("Backup") {
                SettingsRow("Backup", "Automatic backup, network, file types", Icons.Default.Folder, Blue, BlueLight) { nav.navigate(Routes.BACKUP_SETTINGS) }
                SettingsRow("Storage", "Scan locations, permissions, cleanup", Icons.Default.Folder, Green, GreenLight) { nav.navigate(Routes.STORAGE_ACCESS) }
                SettingsRow("Telegram", "Channel configuration, connection", Icons.Default.Send, Blue, BlueLight) { nav.navigate(Routes.DESTINATION) }
            }

            SettingsGroup("Preferences") {
                SettingsRow("Security & Privacy", "Encryption, app lock, data control", Icons.Default.Lock, Orange, OrangeLight) { nav.navigate(Routes.ADVANCED_SETTINGS) }
                SettingsRow("Notifications", "Backup alerts, progress updates", Icons.Default.Notifications, Purple, PurpleLight) { nav.navigate(Routes.BACKUP_SETTINGS) }
                SettingsRow("Appearance", "Theme, language, display", Icons.Default.Palette, Purple, PurpleLight) { nav.navigate(Routes.ADVANCED_SETTINGS) }
            }

            SettingsGroup("Support") {
                SettingsRow("About", "Version, help, open source", Icons.Default.Info, Blue, BlueLight) { nav.navigate(Routes.ABOUT) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 2.dp))
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, icon: ImageVector, accent: Color, iconBackground: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(iconBackground), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
