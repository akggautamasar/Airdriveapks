package com.airdrive.backup.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.ui.nav.Routes

private val Blue = Color(0xFF2F6FEA)
private val BlueLight = Color(0xFFEAF2FF)
private val Green = Color(0xFF20A463)
private val GreenLight = Color(0xFFE5F7EE)
private val Purple = Color(0xFF7C3AED)
private val PurpleLight = Color(0xFFF0E8FF)
private val Orange = Color(0xFFF59E0B)
private val OrangeLight = Color(0xFFFFF4DD)
private val Cyan = Color(0xFF18B8C8)
private val CyanLight = Color(0xFFE2F8FA)
private val Red = Color(0xFFE34D59)
private val RedLight = Color(0xFFFFE8EA)
private val Background = Color(0xFFF7F9FD)

private data class CategoryStyle(
    val icon: ImageVector,
    val color: Color,
    val tint: Color
)

private fun styleFor(category: BackupCategory): CategoryStyle = when (category) {
    BackupCategory.PHOTOS -> CategoryStyle(Icons.Filled.Image, Blue, BlueLight)
    BackupCategory.VIDEOS -> CategoryStyle(Icons.Filled.Movie, Orange, OrangeLight)
    BackupCategory.PDFS -> CategoryStyle(Icons.Filled.Description, Cyan, CyanLight)
    BackupCategory.WORD_EXCEL -> CategoryStyle(Icons.Filled.InsertDriveFile, Green, GreenLight)
    BackupCategory.AUDIO -> CategoryStyle(Icons.Filled.AudioFile, Purple, PurpleLight)
    BackupCategory.CALL_RECORDINGS -> CategoryStyle(Icons.Filled.Phone, Red, RedLight)
    BackupCategory.OTHER_FILES -> CategoryStyle(Icons.Filled.Folder, Blue, BlueLight)
}

@Composable
fun CategoriesStatsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val totals by db.fileRecordDao().categoryTotalsFlow().collectAsState(initial = emptyList())

    val totalFiles = totals.sumOf { it.total }
    val uploadedFiles = totals.sumOf { it.uploaded }
    val totalBytes = totals.sumOf { it.totalBytes }
    val uploadedBytes = totals.sumOf { it.uploadedBytes }
    val pendingFiles = (totalFiles - uploadedFiles).coerceAtLeast(0)
    val completion = if (totalFiles == 0) 0f else uploadedFiles.toFloat() / totalFiles.toFloat()

    var selectedFilter by remember { mutableStateOf("All") }
    val filters = listOf("All", "Photos", "Videos", "Documents", "Audio")
    val visibleCategories = remember(selectedFilter) {
        when (selectedFilter) {
            "Photos" -> listOf(BackupCategory.PHOTOS)
            "Videos" -> listOf(BackupCategory.VIDEOS)
            "Documents" -> listOf(BackupCategory.PDFS, BackupCategory.WORD_EXCEL)
            "Audio" -> listOf(BackupCategory.AUDIO, BackupCategory.CALL_RECORDINGS)
            else -> BackupCategory.values().toList()
        }
    }

    Scaffold(containerColor = Background) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Your files", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Everything protected by AirDrive", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = BlueLight
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Search, contentDescription = "Search files", tint = Blue)
                    }
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(92.dp)) {
                            CircularProgressIndicator(
                                progress = { completion },
                                modifier = Modifier.fillMaxSize(),
                                strokeWidth = 9.dp,
                                color = Green,
                                trackColor = GreenLight
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${(completion * 100).toInt()}%", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("protected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Backup overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "$uploadedFiles of $totalFiles files backed up",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MiniMetric("Protected", uploadedFiles.toString(), Green, GreenLight)
                                MiniMetric("Pending", pendingFiles.toString(), Orange, OrangeLight)
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OverviewStat("Files", totalFiles.toString(), Blue, BlueLight, Modifier.weight(1f))
                    OverviewStat("Cloud", formatBytes(uploadedBytes), Purple, PurpleLight, Modifier.weight(1f))
                    OverviewStat("Local", formatBytes(totalBytes), Cyan, CyanLight, Modifier.weight(1f))
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filters.forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter) },
                            leadingIcon = if (filter == "All") {
                                { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(17.dp)) }
                            } else null,
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                }

                Text("Categories", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(visibleCategories.size) { index ->
                    val category = visibleCategories[index]
                    val row = totals.find { it.category == category }
                    CategoryCard(
                        category = category,
                        total = row?.total ?: 0,
                        uploaded = row?.uploaded ?: 0,
                        totalBytes = row?.totalBytes ?: 0L,
                        uploadedBytes = row?.uploadedBytes ?: 0L,
                        onClick = { nav.navigate("${Routes.CATEGORY_DETAIL}/${category.name}") }
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniMetric(label: String, value: String, color: Color, tint: Color) {
    Surface(shape = RoundedCornerShape(10.dp), color = tint) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(5.dp))
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(3.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OverviewStat(label: String, value: String, color: Color, tint: Color, modifier: Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(13.dp)) {
            Surface(shape = RoundedCornerShape(9.dp), color = tint, modifier = Modifier.size(30.dp)) {
                Box(contentAlignment = Alignment.Center) { Box(Modifier.size(8.dp).clip(CircleShape).background(color)) }
            }
            Spacer(Modifier.height(9.dp))
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CategoryCard(
    category: BackupCategory,
    total: Int,
    uploaded: Int,
    totalBytes: Long,
    uploadedBytes: Long,
    onClick: () -> Unit
) {
    val style = styleFor(category)
    val fraction = if (total == 0) 0f else uploaded.toFloat() / total.toFloat()

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(15.dp), color = style.tint, modifier = Modifier.size(52.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(style.icon, contentDescription = null, tint = style.color, modifier = Modifier.size(27.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(categoryLabel(category), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("$uploaded / $total", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = style.color)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatBytes(uploadedBytes)} backed up • ${formatBytes(totalBytes)} total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(Modifier.height(9.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = style.color,
                    trackColor = style.tint
                )
            }
        }
    }
}
