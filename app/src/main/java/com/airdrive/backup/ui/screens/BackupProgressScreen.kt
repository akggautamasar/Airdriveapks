package com.airdrive.backup.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.repo.BackupPhase
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.work.WorkScheduler

@Composable
fun BackupProgressScreen(nav: NavHostController) {
    val context = LocalContext.current
    // get(), not the constructor: the worker writes to the singleton's StateFlow, and a second
    // instance is exactly why this screen used to sit at "0 / 0 files" during a live backup.
    val repository = remember { BackupRepository.get(context) }
    val progress by repository.progress.collectAsState()

    val scanning = progress.phase == BackupPhase.SCANNING
    val percent = progress.percent

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())
        ) {
            Text("AirDrive Backup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                when (progress.phase) {
                    BackupPhase.IDLE -> "Idle"
                    BackupPhase.SCANNING -> "Scanning storage"
                    BackupPhase.UPLOADING -> "Uploading to Telegram"
                    BackupPhase.FINISHED -> "Finished"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))

            if (scanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(10.dp))
            } else {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(10.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("$percent%", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(16.dp))
            Text("${progress.doneFiles} / ${progress.totalFiles} files", style = MaterialTheme.typography.bodyLarge)
            Text(
                "${formatBytes(progress.effectiveBytes)} / ${formatBytes(progress.totalBytesQueued)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (progress.failedFiles > 0) {
                Text(
                    "${progress.failedFiles} failed — see Failed Uploads",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            progress.statusText?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            progress.currentFileName?.let { name ->
                Spacer(Modifier.height(24.dp))
                Text(if (scanning) "Folder:" else "Current file:", style = MaterialTheme.typography.labelLarge)
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                if (!scanning && progress.currentFileBytes > 0L) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = {
                            (progress.currentFileUploadedBytes.toFloat() /
                                progress.currentFileBytes.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${formatBytes(progress.currentFileUploadedBytes)} / ${formatBytes(progress.currentFileBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("Speed:", style = MaterialTheme.typography.labelLarge)
                    Text(if (progress.bytesPerSecond > 0) "${formatBytes(progress.bytesPerSecond)}/s" else "—")
                }
                Column {
                    Text("ETA:", style = MaterialTheme.typography.labelLarge)
                    Text(formatEta(progress.etaSeconds))
                }
            }

            Spacer(Modifier.height(32.dp))

            if (progress.isRunning) {
                OutlinedButton(
                    onClick = { WorkScheduler.pauseManual(context) },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Pause") }
            } else {
                Button(
                    onClick = { WorkScheduler.runNow(context) },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(if (progress.doneFiles > 0) "Run again" else "Back up now") }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = { nav.navigate(Routes.DASHBOARD) { popUpTo(Routes.DASHBOARD) { inclusive = true } } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Back to dashboard") }
        }
    }
}

private fun formatEta(seconds: Long): String {
    if (seconds <= 0L) return "—"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
