package com.airdrive.backup.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.StorageAccess
import kotlinx.coroutines.launch

private fun lastSegment(uriString: String): String =
    try {
        android.net.Uri.parse(uriString).lastPathSegment ?: uriString
    } catch (e: Exception) {
        uriString
    }

/**
 * Optional now. With "All files access" granted AirDrive walks the whole phone and never needs a
 * picked folder, so nothing on this screen blocks Continue — it exists for devices where that
 * permission is unavailable or declined.
 */
@Composable
fun FolderSelectionScreen(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    val authorizedUris by settings.authorizedTreeUris.collectAsState(initial = emptySet())
    val wholeDevice by settings.scanWholeDevice.collectAsState(initial = true)
    var hasAccess by remember { mutableStateOf(StorageAccess.hasFullAccess(context)) }
    OnResumeEffect { hasAccess = StorageAccess.hasFullAccess(context) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            scope.launch { settings.addAuthorizedTreeUri(uri.toString()) }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text(
                "Specific folders",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (wholeDevice && hasAccess) {
                    "Not needed: AirDrive is already scanning every folder on the phone. Anything " +
                        "you add here is simply included as well."
                } else {
                    "AirDrive will scan only the folders listed here."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(20.dp))
            Text("Authorized folders", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(authorizedUris.toList()) { uriString ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                lastSegment(uriString),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            IconButton(onClick = {
                                scope.launch { settings.removeAuthorizedTreeUri(uriString) }
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove")
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) { Text("+ Choose folders") }
                }
            }

            Button(
                onClick = {
                    // Never disabled: requiring a folder here is what made onboarding a dead end.
                    nav.navigate(Routes.READY)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Continue") }
        }
    }
}
