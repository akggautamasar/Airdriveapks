package com.airdrive.backup.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.work.WorkScheduler
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FailedUploadsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()

    val failed by db.fileRecordDao().failedFilesFlow().collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Failed Uploads (${failed.size})") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { repository.retryAllFailed() }
                        WorkScheduler.runNow(context)
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry all")
                    }
                }
            )
        }
    ) { padding ->
        if (failed.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No failed uploads \uD83C\uDF89", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            // 709 rows all failing for the same reason is a configuration problem, not 709
            // problems, so say so once at the top instead of making the user scroll.
            val commonError = failed.groupingBy { it.lastError ?: "Unknown error" }
                .eachCount().maxByOrNull { it.value }
            if (commonError != null && commonError.value > 1) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "${commonError.value} of these failed the same way",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(commonError.key, style = MaterialTheme.typography.bodyMedium)
                            if (commonError.key.contains("Chat not found", ignoreCase = true) ||
                                commonError.key.contains("channel", ignoreCase = true)
                            ) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Open Channel Configuration and use “Test all channels” — the " +
                                        "signed-in Telegram account has to be a member of every " +
                                        "channel it uploads to.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { nav.navigate(Routes.CHANNEL_CONFIG) }) {
                                    Text("Check channels")
                                }
                            }
                        }
                    }
                }
            }
            items(failed) { record: FileRecord ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(record.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            record.lastError ?: "Unknown error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            "Retries: ${record.retryCount}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            scope.launch { repository.retryOne(record.id) }
                            WorkScheduler.runNow(context)
                        }) { Text("Retry") }
                    }
                }
            }
        }
    }
}
