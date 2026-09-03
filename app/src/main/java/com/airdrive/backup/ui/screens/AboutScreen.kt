package com.airdrive.backup.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.BuildConfig
import com.airdrive.backup.telegram.TdClient
import com.airdrive.backup.data.prefs.SettingsStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(nav: NavHostController) {
    val context = LocalContext.current
    val tdClient = remember { TdClient.get(context) }
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Text("AirDrive", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Version ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text(
                "AirDrive is your personal, Telegram-powered backup client. Files you choose to " +
                    "back up are uploaded to your own private Telegram channels \u2014 nothing is " +
                    "ever sent anywhere else, and originals on your device are never deleted.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(32.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        tdClient.logOut()
                        settings.setTelegramLoggedIn(false)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Disconnect Telegram") }
        }
    }
}
