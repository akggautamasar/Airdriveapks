package com.airdrive.backup.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.StorageAccess
import kotlinx.coroutines.launch

/**
 * Runs [onResume] every time the screen comes back to the foreground. Needed because "All files
 * access" is granted on a system settings page: the only way to notice the grant is to re-check
 * when the user returns to the app.
 */
@Composable
fun OnResumeEffect(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onResume)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) callback()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Replaces the old "pick your folders before you may continue" gate. AirDrive now backs up
 * everything under internal storage by default, so this screen only asks for the one permission
 * that makes that possible — and Continue is never blocked.
 */
@Composable
fun StorageAccessScreen(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var hasAccess by remember { mutableStateOf(StorageAccess.hasFullAccess(context)) }
    OnResumeEffect { hasAccess = StorageAccess.hasFullAccess(context) }

    val legacyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasAccess = StorageAccess.hasFullAccess(context) }

    val wholeDevice by settings.scanWholeDevice.collectAsState(initial = true)
    val includeSdCard by settings.includeSdCard.collectAsState(initial = true)
    val enabledCategories by settings.enabledCategories
        .collectAsState(initial = BackupCategory.values().toSet())

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())
        ) {
            Text(
                "Storage access",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "No folder picking. AirDrive walks every folder in internal storage and sends what " +
                    "it finds to your Telegram channels.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (hasAccess) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (hasAccess) "All files access: granted" else "All files access: needed",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (hasAccess) {
                            StorageAccess.describeRoots(context, includeSdCard)
                        } else if (StorageAccess.grantedFromSettingsScreen) {
                            "Android only lets you turn this on from Settings. Tap below, then " +
                                "switch on “Allow access to manage all files” and come back."
                        } else {
                            "AirDrive needs permission to read your files."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (!hasAccess) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            if (StorageAccess.grantedFromSettingsScreen) {
                                StorageAccess.openAllFilesAccess(context)
                            } else {
                                legacyPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                        }) { Text("Open permission settings") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("What to scan", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = wholeDevice,
                    onCheckedChange = { scope.launch { settings.setScanWholeDevice(it) } }
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Every folder on the phone")
                    Text(
                        "Off: only folders you pick by hand",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = includeSdCard,
                    onCheckedChange = { scope.launch { settings.setIncludeSdCard(it) } },
                    enabled = wholeDevice
                )
                Spacer(Modifier.width(12.dp))
                Text("Include SD card / USB storage")
            }

            Spacer(Modifier.height(20.dp))
            Text("File types", style = MaterialTheme.typography.titleMedium)
            BackupCategory.values().forEach { category ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = category in enabledCategories,
                        onCheckedChange = { checked ->
                            val next = if (checked) enabledCategories + category
                            else enabledCategories - category
                            scope.launch { settings.setEnabledCategories(next) }
                        }
                    )
                    Text(categoryLabel(category))
                }
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { nav.navigate(Routes.FOLDER_SELECT) }) {
                Text("Pick specific folders instead (optional)")
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { nav.navigate(Routes.READY) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Continue") }
            if (!hasAccess) {
                Text(
                    "You can continue without it — AirDrive will only see folders you pick.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}
