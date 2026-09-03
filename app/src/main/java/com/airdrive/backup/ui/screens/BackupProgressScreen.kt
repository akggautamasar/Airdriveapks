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
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.work.WorkScheduler
import kotlinx.coroutines.delay

@Composable
fun BackupProgressScreen(nav: NavHostController) {
    val context = LocalContext.current
    val repository = remember { BackupRepository(context) }
    val progress by repository.progress.collectAsState()

    var lastBytes by remember { mutableStateOf(0L) }
    var speedBps by remember { mutableStateOf(0.0) }

    LaunchedEffect(progress.bytesUploaded) {
        val delta = progress.bytesUploaded - lastBytes
        lastBytes = progress.bytesUploaded
        if (delta > 0) speedBps = delta.toDouble()
        delay(1000)
    }

    val percent = if (progress.totalBytesQueued == 0L) 0
        else ((progress.bytesUploaded * 100) / progress.totalBytesQueued).toInt().coerceIn(0, 100)

    val remainingBytes = (progress.totalBytesQueued - progress.bytesUploaded).coerceAtLeast(0)
    val etaSeconds = if (speedBps > 0) (remainingBytes / speedBps).toInt() else 0

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("AirDrive Backup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))

            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth().height(10.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text("$percent%", style = MaterialTheme.typography.titleMedium)

            Spacer(Modifier.height(16.dp))
            Text(
                "${progress.doneFiles} / ${progress.totalFiles} files",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                "${formatBytes(progress.bytesUploaded)} / ${formatBytes(progress.totalBytesQueued)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
            progress.currentFileName?.let {
                Text("Current:", style = MaterialTheme.typography.labelLarge)
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("Speed:", style = MaterialTheme.typography.labelLarge)
                    Text("${formatBytes(speedBps.toLong())}/s")
                }
                Column {
                    Text("ETA:", style = MaterialTheme.typography.labelLarge)
                    Text(formatEta(etaSeconds))
                }
            }

            Spacer(Modifier.weight(1f))

            if (progress.isRunning) {
                OutlinedButton(
                    onClick = { WorkScheduler.pauseManual(context) },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Pause") }
            } else {
                Button(
                    onClick = { nav.navigate(Routes.DASHBOARD) { popUpTo(Routes.DASHBOARD) { inclusive = true } } },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Done") }
            }
        }
    }
}

private fun formatEta(seconds: Int): String {
    if (seconds <= 0) return "\u2014"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m} min"
}
