package com.airdrive.backup.quantx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.telegram.AuthState
import com.airdrive.backup.telegram.TdClient
import kotlinx.coroutines.launch

private const val ALLOWED_PHONE = "+916307868952"
private val QBlue = Color(0xFF5B5FEF)
private val QPurple = Color(0xFF7C3AED)
private val QBg = Color(0xFFF7F8FC)

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
    var files by remember { mutableStateOf<List<QuantFile>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<QuantStats?>(null) }
    var syncing by remember { mutableStateOf(false) }

    val allowed = identity.filter { !it.isWhitespace() }.replace("-", "") == ALLOWED_PHONE

    fun load() {
        loading = true
        error = null
        scope.launch {
            val result = api.files(selectedCategory, search.takeIf { it.isNotBlank() }, favoritesOnly)
            result.onSuccess { files = it }.onFailure { error = it.message }
            api.stats().onSuccess { stats = it }
            loading = false
        }
    }

    LaunchedEffect(phase, selectedCategory, favoritesOnly) {
        if (phase == "home") load()
    }

    if (!allowed) {
        Surface(Modifier.fillMaxSize(), color = QBg) {
            Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Surface(Modifier.size(78.dp), RoundedCornerShape(24.dp), color = Color(0xFFEDEEFF)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Lock, null, tint = QBlue, modifier = Modifier.size(38.dp)) }
                }
                Spacer(Modifier.height(18.dp))
                Text("QuantxDrive is private", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("This AirDrive feature is enabled only for its authorized Telegram account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                Text("No QuantxDrive data is shown for this account.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(26.dp))
                OutlinedButton(onClick = { nav.popBackStack() }, shape = RoundedCornerShape(14.dp)) { Text("Go back") }
            }
        }
        return
    }

    if (phase == "login") {
        Surface(Modifier.fillMaxSize(), color = QBg) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Surface(Modifier.size(82.dp), RoundedCornerShape(25.dp), color = QBlue) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Cloud, null, tint = Color.White, modifier = Modifier.size(42.dp)) }
                }
                Spacer(Modifier.height(18.dp))
                Text("QuantxDrive", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Private cloud inside AirDrive", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(26.dp))
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Secure access", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("Your authorized account: $ALLOWED_PHONE", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(value = password, onValueChange = { password = it }, singleLine = true, label = { Text("QuantxDrive password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = {
                            loading = true; error = null
                            scope.launch {
                                api.login(ALLOWED_PHONE, password).onSuccess { phase = "home" }.onFailure { error = it.message }
                                loading = false
                            }
                        }, enabled = password.isNotBlank() && !loading, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) { Text(if (loading) "Unlocking…" else "Unlock QuantxDrive") }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("Telegram connection: ${if (authState == AuthState.READY) "Connected" else "Not ready"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    Scaffold(
        containerColor = QBg,
        topBar = {
            TopAppBar(
                title = { Column { Text("QuantxDrive", fontWeight = FontWeight.Bold); Text("Private storage", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Text("‹", style = MaterialTheme.typography.headlineMedium) } },
                actions = {
                    IconButton(onClick = { load() }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    IconButton(onClick = { api.logout(); phase = "login" }) { Icon(Icons.Default.Lock, contentDescription = "Lock") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFEDEEFF))) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(QBlue), contentAlignment = Alignment.Center) { Icon(Icons.Default.Cloud, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
                        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
                            Text("Your private cloud", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("QuantxDrive is available only to $ALLOWED_PHONE", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniStat(stats?.totalFiles?.toString() ?: "—", "Files", Modifier.weight(1f))
                        MiniStat(formatBytes(stats?.totalBytes ?: 0), "Storage", Modifier.weight(1f))
                        MiniStat(stats?.favorites?.toString() ?: "—", "Favorites", Modifier.weight(1f))
                    }
                }
            }

            OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (search.isNotEmpty()) TextButton(onClick = { search = ""; load() }) { Text("Clear") } }, placeholder = { Text("Search your QuantxDrive") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(15.dp))

            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedCategory == null, onClick = { selectedCategory = null }, label = { Text("All") })
                FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly }, label = { Text("Favorites") }, leadingIcon = { Icon(Icons.Default.Favorite, null, modifier = Modifier.size(16.dp)) })
                listOf("photos" to "Photos", "videos" to "Videos", "pdfs" to "PDFs", "audio" to "Audio").forEach { (key, label) -> FilterChip(selected = selectedCategory == key, onClick = { selectedCategory = if (selectedCategory == key) null else key }, label = { Text(label) }) }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (loading) "Loading…" else "${files.size} files", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                TextButton(enabled = !syncing, onClick = {
                    syncing = true
                    scope.launch { api.sync().onFailure { error = it.message }; syncing = false; load() }
                }) { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Sync") }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(files, key = { it.id }) { file -> QuantFileRow(file, api) {
                    scope.launch { api.toggleFavorite(file.id); load() }
                } }
            }
        }
    }
}

@Composable private fun QuantFileRow(file: QuantFile, api: QuantxDriveApi, onFavorite: () -> Unit) {
    val icon = when { file.category == "photos" -> Icons.Default.Image; file.category == "videos" -> Icons.Default.VideoLibrary; file.category == "audio" -> Icons.Default.MusicNote; file.category == "pdfs" -> Icons.Default.Description; else -> Icons.Default.Folder }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF0F0F8)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = QPurple, modifier = Modifier.size(25.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.filename, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("${formatBytes(file.size)} • ${file.category.ifBlank { "other" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onFavorite) { Icon(Icons.Default.Favorite, null, tint = if (file.favorite) QPurple else MaterialTheme.colorScheme.onSurfaceVariant) }
            if (file.tgLink != null) IconButton(onClick = { /* Telegram deep-link action is intentionally kept in the web app for now. */ }) { Icon(Icons.Default.Send, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun MiniStat(value: String, label: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Color.White) { Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(value, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    bytes < 1024L * 1024L * 1024L -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    else -> "${"%.2f".format(bytes / 1024.0 / 1024.0 / 1024.0)} GB"
}
