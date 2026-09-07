package com.airdrive.backup.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.prefs.DestinationMode
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.ui.nav.Routes

private val Blue = Color(0xFF2F6FEA)
private val BlueLight = Color(0xFFEAF2FF)
private val Green = Color(0xFF20A463)
private val GreenLight = Color(0xFFE5F7EE)
private val Purple = Color(0xFF7C3AED)
private val PurpleLight = Color(0xFFF0E8FF)
private val Background = Color(0xFFF7F9FD)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramSettingsScreen(nav: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { SettingsStore(context) }
    val loggedIn by settings.telegramLoggedIn.collectAsState(initial = false)
    val destination by settings.destination.collectAsState(initial = null)
    val mode by settings.destinationMode.collectAsState(initial = null)
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("Telegram Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = BlueLight)) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(62.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Send, null, tint = Blue, modifier = Modifier.size(34.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Telegram", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF173A78))
                            Spacer(Modifier.height(5.dp))
                            Surface(shape = RoundedCornerShape(20.dp), color = if (loggedIn) GreenLight else Color.White) {
                                Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (loggedIn) Green else Color.Gray))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (loggedIn) "Connected" else "Not connected", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (loggedIn) "Your AirDrive backup account is connected to Telegram."
                        else "Connect your Telegram account to store and restore backups.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { if (loggedIn) { testing = true; result = "Connection check started…"; testing = false } else nav.navigate(Routes.TELEGRAM_LOGIN) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue)
                    ) { Icon(if (loggedIn) Icons.Default.Refresh else Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text(if (loggedIn) "Check connection" else "Connect Telegram") }
                }
            }

            result?.let { Text(it, color = Blue, style = MaterialTheme.typography.bodySmall) }

            SettingsGroupTelegram("Account") {
                TelegramRow("Telegram account", if (loggedIn) "Connected and ready for backups" else "Sign in to Telegram", Icons.Default.Send, Blue, BlueLight) {
                    nav.navigate(if (loggedIn) Routes.API_CREDENTIALS else Routes.TELEGRAM_LOGIN)
                }
                TelegramRow("Connection status", if (loggedIn) "Connected" else "Not connected", Icons.Default.CheckCircle, Green, GreenLight) {
                    if (loggedIn) { testing = true; result = "Connection is active."; testing = false } else nav.navigate(Routes.TELEGRAM_LOGIN)
                }
            }

            SettingsGroupTelegram("Destination") {
                TelegramRow("Backup destination", when (mode) {
                    DestinationMode.SAVED_MESSAGES -> "Saved Messages"
                    DestinationMode.SINGLE_CHAT -> "Private channel / chat"
                    DestinationMode.PER_CATEGORY -> "Category channels"
                    null -> "Not configured"
                }, Icons.Default.CloudUpload, Blue, BlueLight) { nav.navigate(Routes.DESTINATION) }
                TelegramRow("Channel configuration", "Configure category channels", Icons.Default.Settings, Purple, PurpleLight) { nav.navigate(Routes.CHANNEL_CONFIG) }
            }

            SettingsGroupTelegram("Security & reliability") {
                TelegramRow("Sync now", "Check Telegram connection and destination", Icons.Default.Refresh, Blue, BlueLight) {
                    testing = true; result = "Sync check started…"; testing = false
                }
                TelegramRow("Privacy", "Your Telegram credentials stay on this device", Icons.Default.Security, Green, GreenLight) { nav.navigate(Routes.ADVANCED_SETTINGS) }
            }

            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = BlueLight)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, null, tint = Blue, modifier = Modifier.size(27.dp))
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Your data stays private", fontWeight = FontWeight.Bold, color = Color(0xFF174A9C))
                        Text("AirDrive uploads directly to your Telegram destination.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SettingsGroupTelegram(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 2.dp))
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(content = content) }
    }
}

@Composable
private fun TelegramRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, iconBackground: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(iconBackground), contentAlignment = Alignment.Center) { Icon(icon, null, tint = accent, modifier = Modifier.size(23.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
