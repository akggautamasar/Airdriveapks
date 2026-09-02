package com.airdrive.backup.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.CategoryTotals
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.StorageAccess
import com.airdrive.backup.work.WorkScheduler
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(nav: NavHostController) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }

    val uploadedCount by db.fileRecordDao().uploadedCountFlow().collectAsState(initial = 0)
    val pendingCount by db.fileRecordDao().pendingCountFlow().collectAsState(initial = 0)
    val failedCount by db.fileRecordDao().failedCountFlow().collectAsState(initial = 0)
    val uploadedBytes by db.fileRecordDao().uploadedBytesFlow().collectAsState(initial = 0L)
    val lastBackup by db.fileRecordDao().lastBackupTimeFlow().collectAsState(initial = null)
    val categoryTotals by db.fileRecordDao().categoryTotalsFlow().collectAsState(initial = emptyList())

    var hasAccess by remember { mutableStateOf(StorageAccess.hasFullAccess(context)) }
    OnResumeEffect { hasAccess = StorageAccess.hasFullAccess(context) }

    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AirDrive") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Channel configuration") }, onClick = {
                            menuOpen = false; nav.navigate(Routes.CHANNEL_CONFIG)
                        })
                        DropdownMenuItem(text = { Text("Storage access") }, onClick = {
                            menuOpen = false; nav.navigate(Routes.STORAGE_ACCESS)
                        })
                        DropdownMenuItem(text = { Text("Backup settings") }, onClick = {
                            menuOpen = false; nav.navigate(Routes.BACKUP_SETTINGS)
                        })
                        DropdownMenuItem(text = { Text("Failed uploads") }, onClick = {
                            menuOpen = false; nav.navigate(Routes.FAILED_UPLOADS)
                        })
                        DropdownMenuItem(text = { Text("About") }, onClick = {
                            menuOpen = false; nav.navigate(Routes.ABOUT)
                        })
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp)
        ) {
            Text("Backup Status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Last backup: ${formatLastBackup(lastBackup)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            if (!hasAccess) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Storage access is off", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "AirDrive can only see folders you picked by hand.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { nav.navigate(Routes.STORAGE_ACCESS) }) { Text("Fix this") }
                    }
                }
            }

            Button(
                onClick = { WorkScheduler.runNow(context); nav.navigate(Routes.BACKUP_PROGRESS) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("BACK UP NOW") }

            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatTile("Files backed up", uploadedCount.toString(), Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                StatTile("Storage uploaded", formatBytes(uploadedBytes), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatTile("Pending", pendingCount.toString(), Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                StatTile("Failed", failedCount.toString(), Modifier.weight(1f))
            }

            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Categories", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = { nav.navigate(Routes.CATEGORIES_STATS) }) { Text("View all") }
            }
            Spacer(Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f)
            ) {
                // Every category is listed, whether or not anything has uploaded yet, and the
                // counts include queued files: an empty grid told the user nothing.
                items(BackupCategory.values().toList()) { category ->
                    val row: CategoryTotals? = categoryTotals.find { it.category == category }
                    Card(modifier = Modifier.padding(6.dp).fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(categoryLabel(category), style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${row?.uploaded ?: 0}/${row?.total ?: 0} files \u2022 " +
                                    formatBytes(row?.totalBytes ?: 0L),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { nav.navigate(Routes.ACTIVITY_HISTORY) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("View Activity") }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatLastBackup(millis: Long?): String {
    if (millis == null) return "Never"
    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return fmt.format(Date(millis))
}
