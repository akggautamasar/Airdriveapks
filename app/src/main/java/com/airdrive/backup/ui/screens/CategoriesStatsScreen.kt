package com.airdrive.backup.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.*
import com.airdrive.backup.ui.nav.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesStatsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val totals by db.fileRecordDao().categoryTotalsFlow().collectAsState(initial = emptyList())
    val totalFiles = totals.sumOf { it.total }
    val uploadedFiles = totals.sumOf { it.uploaded }
    val totalBytes = totals.sumOf { it.totalBytes }
    val uploadedBytes = totals.sumOf { it.uploadedBytes }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categories & Statistics") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("All categories", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("$uploadedFiles of $totalFiles files backed up", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${formatBytes(uploadedBytes)} of ${formatBytes(totalBytes)}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(BackupCategory.values().toList()) { category ->
                val row = totals.find { it.category == category }
                val total = row?.total ?: 0
                val uploaded = row?.uploaded ?: 0
                val fraction = if (total == 0) 0f else uploaded.toFloat() / total.toFloat()
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { nav.navigate("${Routes.CATEGORY_DETAIL}/${category.name}") },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(categoryLabel(category), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text("$uploaded / $total files", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("${formatBytes(row?.uploadedBytes ?: 0L)} of ${formatBytes(row?.totalBytes ?: 0L)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(5.dp),
                            trackColor = MaterialTheme.colorScheme.surface
                        )
                    }
                }
            }
        }
    }
}
