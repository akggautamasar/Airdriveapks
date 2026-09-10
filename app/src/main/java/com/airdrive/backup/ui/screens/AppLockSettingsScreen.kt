package com.airdrive.backup.ui.screens

import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.prefs.AppLockStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockSettingsScreen(nav: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { AppLockStore(context) }
    var enabled by remember { mutableStateOf(store.isEnabled()) }
    val scope = rememberCoroutineScope()
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val available = remember(context) {
        BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    Scaffold(topBar = { TopAppBar(title = { Text("App Lock") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Fingerprint,
                        null,
                        modifier = Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Protect AirDrive", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Ask for biometric or device PIN when AirDrive opens.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        enabled = available,
                        onCheckedChange = { value ->
                            enabled = value
                            scope.launch { store.setEnabled(value) }
                        }
                    )
                }
            }

            Text(
                if (available) {
                    "Optional and OFF by default. AirDrive never stores your fingerprint, face data, PIN, or password."
                } else {
                    "Set up a secure device lock or supported biometric in Android Settings first."
                },
                color = if (available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )

            OutlinedButton(
                onClick = { nav.popBackStack() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Lock, null)
                Spacer(Modifier.width(8.dp))
                Text("Done")
            }
        }
    }
}
