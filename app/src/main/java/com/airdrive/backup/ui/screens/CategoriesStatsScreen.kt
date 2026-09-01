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
import com.airdrive.backup.data.db.CategoryCount

private fun categoryLabelFor(c: BackupCategory) = when (c) {
    BackupCategory.PHOTOS -> "Photos"
    BackupCategory.VIDEOS -> "Videos"
    BackupCategory.PDFS -> "PDFs"
    BackupCategory.WORD_EXCEL -> "Documents"
    BackupCategory.AUDIO -> "Audio"
    BackupCategory.CALL_RECORDINGS -> "Call Recordings"
    BackupCategory.OTHER_FILES -> "Other Files"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesStatsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val breakdown by db.fileRecordDao().categoryBreakdownFlow().collectAsState(initial = emptyList())
    val totalUploaded = breakdown.sumOf { it.count }

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
            items(BackupCategory.values().toList()) { category ->
                val cc: CategoryCount? = breakdown.find { it.category == category }
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(categoryLabelFor(category), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${cc?.count ?: 0} files")
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            formatBytes(cc?.bytes ?: 0L),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (totalUploaded > 0 && cc != null) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { cc.count.toFloat() / totalUploaded },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
