package com.airdrive.backup.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.work.WorkScheduler
import kotlinx.coroutines.launch

@Composable
fun ReadyScreen(nav: NavHostController) {
    val context = LocalContext.current
    val repository = remember { BackupRepository(context) }
    val settings = remember { SettingsStore(context) }
    val db = remember { AppDatabase.get(context) }
    val scope = rememberCoroutineScope()

    var scanning by remember { mutableStateOf(true) }
    var fileCount by remember { mutableStateOf(0) }
    var totalBytes by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        repository.scan()
        val pending = db.fileRecordDao().pendingFiles()
        fileCount = pending.size
        totalBytes = pending.sumOf { it.sizeBytes }
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
                Text("Scanning your folders\u2026", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Files found: $fileCount", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Estimated size: ${formatBytes(totalBytes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(40.dp))
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
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Start First Backup") }
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    if (gb >= 1) return String.format("%.1f GB", gb)
    val mb = bytes / 1024.0 / 1024.0
    return String.format("%.1f MB", mb)
}
