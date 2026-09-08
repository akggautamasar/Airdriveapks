package com.airdrive.backup.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    val context = androidx.compose.ui.platform.LocalContext.current
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
    val blue = Color(0xFF2F6FEA)
    val blueSoft = Color(0xFFEAF2FF)

    LaunchedEffect(scanNonce) {
        scanning = true
        repository.scan()
        val (count, bytes) = repository.pendingSummary()
        fileCount = count
        totalBytes = bytes
        scanning = false
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F9FD)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(76.dp).clip(CircleShape).background(blueSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, tint = blue, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(if (scanning) "Preparing your backup" else "Ready to back up", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))

            if (scanning) {
                CircularProgressIndicator(color = blue, modifier = Modifier.padding(vertical = 12.dp))
                Text(progress.statusText ?: "Scanning your storage…", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                progress.currentFileName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
            } else {
                Text("$fileCount files ready", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Estimated size: ${formatBytes(totalBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(blueSoft), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = blue, modifier = Modifier.size(23.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Internal storage", fontWeight = FontWeight.SemiBold)
                            Text(StorageAccess.describeRoots(context, includeRemovable = true), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (lastScan?.accessBlocked == true) {
                    Spacer(Modifier.height(12.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Storage access is limited", fontWeight = FontWeight.Bold)
                            Text("Allow full access so AirDrive can scan every folder.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                if (!StorageAccess.openAllFilesAccess(context)) nav.navigate(Routes.STORAGE_ACCESS)
                            }) { Text("Grant access") }
                        }
                    }
                }

                if (destination?.needsSetup == true) {
                    Spacer(Modifier.height(12.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = blueSoft), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Choose your backup destination", fontWeight = FontWeight.Bold)
                            Text("Saved Messages is ready to use, or choose a channel.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { scope.launch { settings.setDestinationMode(DestinationMode.SAVED_MESSAGES) } }) { Text("Use Saved Messages") }
                                TextButton(onClick = { nav.navigate(Routes.DESTINATION) }) { Text("Choose channel") }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            if (!scanning) {
                Button(
                    onClick = {
                        scope.launch {
                            settings.setOnboardingDone(true)
                            WorkScheduler.rescheduleAutoBackup(context)
                            WorkScheduler.runNow(context)
                            // Put the real Home destination underneath progress. This prevents
                            // Back from returning to Ready and triggering another storage scan.
                            nav.navigate(Routes.DASHBOARD) {
                                popUpTo(Routes.READY) { inclusive = true }
                                launchSingleTop = true
                            }
                            nav.navigate(Routes.BACKUP_PROGRESS)
                        }
                    },
                    enabled = destination?.needsSetup == false,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = blue)
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start first backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { scanNonce++ }) { Text("Scan again") }
            }
            Text("Your backup runs safely in the background.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
        }
    }
}

fun formatBytes(bytes: Long): String = com.airdrive.backup.util.Format.bytes(bytes)
