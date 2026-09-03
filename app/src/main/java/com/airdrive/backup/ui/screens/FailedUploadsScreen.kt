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
import com.airdrive.backup.work.WorkScheduler
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FailedUploadsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val repository = remember { BackupRepository(context) }
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
