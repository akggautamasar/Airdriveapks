package com.airdrive.backup.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.CategoryTotals

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesStatsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    // categoryTotalsFlow, not categoryBreakdownFlow: the latter counts UPLOADED rows only, which
    // is why every category read "0 files, 0.0 MB" while thousands of files sat queued.
    val totals by db.fileRecordDao().categoryTotalsFlow().collectAsState(initial = emptyList())
    val queuedFiles = totals.sumOf { it.total }
    val uploadedFiles = totals.sumOf { it.uploaded }
    val uploadedBytes = totals.sumOf { it.uploadedBytes }
    val totalBytes = totals.sumOf { it.totalBytes }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categories & Statistics") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("All categories", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("$uploadedFiles of $queuedFiles files backed up")
                        Text(
                            "${formatBytes(uploadedBytes)} of ${formatBytes(totalBytes)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(BackupCategory.values().toList()) { category ->
                val row: CategoryTotals? = totals.find { it.category == category }
                val total = row?.total ?: 0
                val uploaded = row?.uploaded ?: 0
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                categoryLabel(category),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text("$uploaded / $total files")
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatBytes(row?.uploadedBytes ?: 0L)} of ${formatBytes(row?.totalBytes ?: 0L)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (total > 0) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { uploaded.toFloat() / total.toFloat() },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
