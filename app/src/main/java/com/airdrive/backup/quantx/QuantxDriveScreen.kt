package com.airdrive.backup.quantx

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.telegram.AuthState
import com.airdrive.backup.telegram.TdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val ALLOWED_PHONE = "+916307868952"
private val QBlue = Color(0xFF5B5FEF)
private val QPurple = Color(0xFF7C3AED)
private val QBg = Color(0xFFF7F8FC)
private enum class QuantViewMode { LIST, COMPACT, GRID }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuantxDriveScreen(nav: NavHostController) {
    val context = LocalContext.current
    val api = remember { QuantxDriveApi(context) }
    val tdClient = remember { TdClient.get(context) }
    val scope = rememberCoroutineScope()
    val authState by tdClient.authState.collectAsState()
    val identity = remember { context.getSharedPreferences("quantxdrive_identity", 0).getString("phone", "") ?: "" }
    var phase by remember { mutableStateOf(if (api.savedToken != null) "home" else "login") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<QuantFile>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var stats by remember { mutableStateOf<QuantStats?>(null) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var total by remember { mutableStateOf<Int?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<QuantFile?>(null) }
    var modeName by rememberSaveable { mutableStateOf(QuantViewMode.LIST.name) }
    val mode = QuantViewMode.valueOf(modeName)
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val allowed = identity.filter { !it.isWhitespace() }.replace("-", "") == ALLOWED_PHONE

    suspend fun reload() {
        loading = true; loadingMore = false; error = null; page = 1; hasMore = false; total = null
        api.files(1, 100, selectedCategory, search.takeIf { it.isNotBlank() }, favoritesOnly)
            .onSuccess { files = it.files; total = it.total; hasMore = it.hasMore }
            .onFailure { error = it.message }
        loading = false
    }
    suspend fun more() {
        if (loading || loadingMore || !hasMore) return
        loadingMore = true
        val next = page + 1
        api.files(next, 100, selectedCategory, search.takeIf { it.isNotBlank() }, favoritesOnly)
            .onSuccess { result ->
                val ids = files.asSequence().map { it.id }.toHashSet()
                files = files + result.files.filterNot { it.id in ids }
                page = next; total = result.total ?: total; hasMore = result.hasMore
            }.onFailure { error = it.message }
        loadingMore = false
    }

    LaunchedEffect(phase, selectedCategory, favoritesOnly, search) {
        if (phase == "home") { if (search.isNotBlank()) delay(350); reload() }
    }
    LaunchedEffect(phase) {
        if (phase != "home") return@LaunchedEffect
        api.stats().onSuccess { stats = it; if (it.categoryCounts.isNotEmpty()) counts = it.categoryCounts }
        api.categoryCounts().onSuccess { if (it.isNotEmpty()) counts = it }
    }
    LaunchedEffect(mode) {
        if (phase != "home") return@LaunchedEffect
        snapshotFlow { if (mode == QuantViewMode.GRID) gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 else listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { index -> if (index >= (files.size - 15).coerceAtLeast(0)) more() }
    }

    if (!allowed) {
        Surface(Modifier.fillMaxSize(), color = QBg) { Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Lock, null, tint = QBlue, modifier = Modifier.size(60.dp)); Spacer(Modifier.height(18.dp))
            Text("QuantxDrive is private", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp)); Text("This feature is enabled only for its authorized Telegram account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp)); OutlinedButton(onClick = { nav.popBackStack() }) { Text("Go back") }
        } }; return
    }
    if (phase == "login") {
        Surface(Modifier.fillMaxSize(), color = QBg) { Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Cloud, null, tint = QBlue, modifier = Modifier.size(70.dp)); Spacer(Modifier.height(12.dp))
            Text("QuantxDrive", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Private cloud inside AirDrive", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp)); Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(20.dp)) {
                Text("Secure access", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(14.dp))
                OutlinedTextField(password, { password = it }, label = { Text("QuantxDrive password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp)); Button(enabled = password.isNotBlank() && !loading, onClick = { loading = true; error = null; scope.launch { api.login(ALLOWED_PHONE, password).onSuccess { phase = "home" }.onFailure { error = it.message }; loading = false } }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (loading) "Unlocking…" else "Unlock QuantxDrive") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp)) }
            } }; Spacer(Modifier.height(16.dp)); Text("Telegram connection: ${if (authState == AuthState.READY) "Connected" else "Not ready"}", style = MaterialTheme.typography.labelMedium)
        } }; return
    }

    Scaffold(containerColor = QBg, topBar = { TopAppBar(
        title = { Column { Text("QuantxDrive", fontWeight = FontWeight.Bold); Text("${total ?: stats?.totalFiles ?: files.size} files", style = MaterialTheme.typography.labelSmall) } },
        navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
        actions = { ViewModeButton(mode) { modeName = it.name }; IconButton({ scope.launch { reload() } }) { Icon(Icons.Default.Refresh, "Refresh") }; IconButton({ api.logout(); phase = "login" }) { Icon(Icons.Default.Lock, "Lock") } }
    ) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                Card(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFEDEEFF))) { Column(Modifier.padding(16.dp)) {
                    Text("Your private cloud", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniStat((stats?.totalFiles ?: total ?: files.size).toString(), "Files", Modifier.weight(1f)); MiniStat(formatBytes(stats?.totalBytes ?: 0), "Storage", Modifier.weight(1f)); MiniStat((stats?.favorites ?: 0).toString(), "Favorites", Modifier.weight(1f))
                    }
                } }
                OutlinedTextField(search, { search = it }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (search.isNotEmpty()) IconButton({ search = "" }) { Icon(Icons.Default.Clear, "Clear") } }, placeholder = { Text("Search your QuantxDrive") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(15.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryChip("All", null, selectedCategory == null && !favoritesOnly, null) { selectedCategory = null; favoritesOnly = false }
                    CategoryChip("Favorites", "favorites", favoritesOnly, stats?.favorites) { favoritesOnly = !favoritesOnly; if (favoritesOnly) selectedCategory = null }
                    listOf("photos" to "Photos", "videos" to "Videos", "audio" to "Audio", "pdfs" to "PDFs", "word_excel" to "Office", "call_recordings" to "Calls", "other_files" to "Other").forEach { (key, label) -> CategoryChip(label, key, selectedCategory == key && !favoritesOnly, counts[normalizeCategory(key)]) { favoritesOnly = false; selectedCategory = if (selectedCategory == key) null else key } }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (loading) "Loading…" else "${files.size} loaded • ${total ?: files.size} total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.weight(1f))
                    TextButton(enabled = !syncing, onClick = { syncing = true; scope.launch { api.sync().onFailure { error = it.message }; syncing = false; reload(); api.stats().onSuccess { stats = it } } }) { if (syncing) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Sync, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Sync") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                when (mode) {
                    QuantViewMode.LIST, QuantViewMode.COMPACT -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(if (mode == QuantViewMode.COMPACT) 5.dp else 9.dp)) {
                        items(files, key = { it.id }) { file -> QuantFileRow(file, mode == QuantViewMode.COMPACT, { selectedFile = file }, { scope.launch { api.toggleFavorite(file.id); reload() } }, { shareFile(context, api, file, scope) }, { file.tgLink?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } } }) }
                        if (loadingMore) item { LoadingFooter() } else if (hasMore) item { LoadMoreButton { scope.launch { more() } } }
                    }
                    QuantViewMode.GRID -> LazyVerticalGrid(GridCells.Fixed(2), state = gridState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(files, key = { it.id }) { file -> QuantFileGridCard(file, { selectedFile = file }, { scope.launch { api.toggleFavorite(file.id); reload() } }) }
                        if (loadingMore) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) { LoadingFooter() } else if (hasMore) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) { LoadMoreButton { scope.launch { more() } } }
                    }
                }
            }
            selectedFile?.let { file -> Surface(Modifier.fillMaxSize(), color = QBg) { QuantxDriveViewerScreen(file, api, { selectedFile = null }) { scope.launch { api.toggleFavorite(file.id); reload() } } } }
        }
    }
}

@Composable private fun CategoryChip(label: String, key: String?, selected: Boolean, count: Int?, onClick: () -> Unit) { FilterChip(selected, onClick, label = { Text(if (count != null) "$label $count" else label) }, leadingIcon = if (key == "favorites") ({ Icon(Icons.Default.Favorite, null, modifier = Modifier.size(16.dp)) }) else null) }
@Composable private fun ViewModeButton(mode: QuantViewMode, onChange: (QuantViewMode) -> Unit) { var expanded by remember { mutableStateOf(false) }; Box { IconButton({ expanded = true }) { Icon(when(mode) { QuantViewMode.LIST -> Icons.Default.ViewList; QuantViewMode.COMPACT -> Icons.Default.ViewAgenda; QuantViewMode.GRID -> Icons.Default.GridView }, "Change view") }; DropdownMenu(expanded, { expanded = false }) { DropdownMenuItem({ Text("List view") }, { onChange(QuantViewMode.LIST); expanded = false }, leadingIcon = { Icon(Icons.Default.ViewList, null) }); DropdownMenuItem({ Text("Compact view") }, { onChange(QuantViewMode.COMPACT); expanded = false }, leadingIcon = { Icon(Icons.Default.ViewAgenda, null) }); DropdownMenuItem({ Text("Grid view") }, { onChange(QuantViewMode.GRID); expanded = false }, leadingIcon = { Icon(Icons.Default.GridView, null) }) } } }
@Composable private fun QuantFileRow(file: QuantFile, compact: Boolean, onOpen: () -> Unit, onFavorite: () -> Unit, onShare: () -> Unit, onTelegram: () -> Unit) { Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(if(compact) 13.dp else 17.dp)) { Row(Modifier.padding(if(compact) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) { QuantFileThumbnail(file, Modifier.size(if(compact) 44.dp else 50.dp)); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(file.filename, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${formatBytes(file.size)} • ${file.category.ifBlank { "other" }}", style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant) }; IconButton(onFavorite) { Icon(Icons.Default.Favorite, null, tint = if(file.favorite) QPurple else MaterialTheme.colorScheme.onSurfaceVariant) }; if(!compact) IconButton(onShare) { Icon(Icons.Default.Share, "Share") }; if(!compact && file.tgLink != null) IconButton(onTelegram) { Icon(Icons.Default.Send, "Telegram") } } } }
@Composable private fun QuantFileGridCard(file: QuantFile, onOpen: () -> Unit, onFavorite: () -> Unit) { Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(17.dp)) { Column { QuantFileThumbnail(file, Modifier.fillMaxWidth().aspectRatio(1f)); Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(file.filename, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(formatBytes(file.size), style = MaterialTheme.typography.labelSmall) }; IconButton(onFavorite, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Favorite, null, tint = if(file.favorite) QPurple else MaterialTheme.colorScheme.onSurfaceVariant) } } } } }
@Composable private fun QuantFileThumbnail(file: QuantFile, modifier: Modifier) { val context = LocalContext.current; val isImage = file.mime.startsWith("image/") || file.filename.lowercase().matches(Regex(".*\\.(jpg|jpeg|png|webp|gif|bmp|heic|heif)$")); val url = remember(file.id, isImage) { if(isImage) QuantxDriveApi(context).mediaUrl(file.id) else null }; val bitmap by produceState<Bitmap?>(null, url) { value = withContext(Dispatchers.IO) { if(url.isNullOrBlank()) null else runCatching { (URL(url).openConnection() as HttpURLConnection).run { connectTimeout=10000; readTimeout=20000; inputStream.use(BitmapFactory::decodeStream) } }.getOrNull() } }; Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFFF0F0F8)), contentAlignment = Alignment.Center) { if(bitmap != null) Image(bitmap!!.asImageBitmap(), "Preview", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Icon(when(normalizeCategory(file.category)) { "photos" -> Icons.Default.Image; "videos" -> Icons.Default.VideoLibrary; "audio" -> Icons.Default.MusicNote; "pdfs" -> Icons.Default.PictureAsPdf; else -> Icons.Default.InsertDriveFile }, null, tint = QPurple, modifier = Modifier.size(27.dp)) } }
@Composable private fun LoadingFooter() { Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Loading more files…") } }
@Composable private fun LoadMoreButton(onClick: () -> Unit) { Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { OutlinedButton(onClick) { Icon(Icons.Default.ExpandMore, null); Spacer(Modifier.width(6.dp)); Text("Load more files") } } }
private fun shareFile(context: Context, api: QuantxDriveApi, file: QuantFile, scope: kotlinx.coroutines.CoroutineScope) { scope.launch { api.createShare(file.id).onSuccess { share -> context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_SUBJECT,file.filename); putExtra(Intent.EXTRA_TEXT,api.sharedStreamUrl(share.token)) }, "Share file")) } } }
@Composable private fun MiniStat(value: String, label: String, modifier: Modifier) { Surface(modifier, shape = RoundedCornerShape(14.dp), color = Color.White) { Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(value, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.labelSmall) } } }
private fun formatBytes(bytes: Long): String = when { bytes <= 0L -> "0 B"; bytes < 1024L*1024L -> "${bytes/1024L} KB"; bytes < 1024L*1024L*1024L -> "${"%.1f".format(bytes/1024.0/1024.0)} MB"; else -> "${"%.2f".format(bytes/1024.0/1024.0/1024.0)} GB" }
