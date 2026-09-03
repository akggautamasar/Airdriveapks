package com.airdrive.backup.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.prefs.DestinationMode
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.StorageAccess
import com.airdrive.backup.work.WorkScheduler
import kotlinx.coroutines.launch

@Composable
fun ReadyScreen(nav: NavHostController) {
    val context = LocalContext.current
    val repository = remember { BackupRepository.get(context) }
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    val progress by repository.progress.collectAsState()
    val lastScan by repository.lastScan.collectAsState()
    val destination by settings.destination.collectAsState(initial = null)

    var scanning by remember { mutableStateOf(true) }
    var fileCount by remember { mutableStateOf(0) }
    var totalBytes by remember { mutableStateOf(0L) }
    var scanNonce by remember { mutableStateOf(0) }

    // scan() hops to Dispatchers.IO itself, so launching it from here does not block the UI.
    LaunchedEffect(scanNonce) {
        scanning = true
        repository.scan()
        val (count, bytes) = repository.pendingSummary()
        fileCount = count
        totalBytes = bytes
        scanning = false
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Ready", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))

            if (scanning) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    progress.statusText ?: "Scanning your storage…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                progress.currentFileName?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Text("Files to back up: $fileCount", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Estimated size: ${formatBytes(totalBytes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (lastScan?.accessBlocked == true) {
                    Spacer(Modifier.height(20.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Only picked folders were scanned",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Turn on “All files access” and AirDrive will back up every folder " +
                                    "on the phone without you choosing any.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = {
                                if (!StorageAccess.openAllFilesAccess(context)) {
                                    nav.navigate(Routes.STORAGE_ACCESS)
                                }
                            }) { Text("Grant access") }
                        }
                    }
                } else if (lastScan?.wholeDevice == true) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        StorageAccess.describeRoots(context, includeRemovable = true),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(32.dp))

                // Last onboarding decision: without a destination the first backup would queue
                // thousands of files and upload none of them.
                if (destination?.needsSetup == true) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Where should backups go?",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Saved Messages needs no setup at all. You can switch to channels " +
                                    "later without losing anything.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = {
                                scope.launch {
                                    settings.setDestinationMode(DestinationMode.SAVED_MESSAGES)
                                }
                            }) { Text("Use Saved Messages") }
                            TextButton(onClick = { nav.navigate(Routes.DESTINATION) }) {
                                Text("Choose a channel instead")
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }

                Button(
                    onClick = {
                        scope.launch {
                            settings.setOnboardingDone(true)
                            WorkScheduler.rescheduleAutoBackup(context)
                            WorkScheduler.runNow(context)
                            nav.navigate(Routes.BACKUP_PROGRESS) {
                                popUpTo(Routes.WELCOME) { inclusive = true }
                            }
                        }
                    },
                    enabled = destination?.needsSetup == false,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Start First Backup") }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { scanNonce++ }) { Text("Scan again") }
            }
        }
    }
}

fun formatBytes(bytes: Long): String = com.airdrive.backup.util.Format.bytes(bytes)
