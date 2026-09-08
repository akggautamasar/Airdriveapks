package com.airdrive.backup.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.backup.TelegramSyncState
import com.airdrive.backup.data.backup.TelegramSyncStateStore
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.telegram.ChannelCheck
import com.airdrive.backup.telegram.TdClient
import com.airdrive.backup.work.WorkScheduler
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelConfigScreen(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val repository = remember { BackupRepository.get(context) }
    val stateStore = remember { TelegramSyncStateStore(context) }
    val scope = rememberCoroutineScope()
    val channelMap by settings.allChannels.collectAsState(initial = null)
    val syncState by stateStore.state.collectAsState(initial = TelegramSyncState())
    var testing by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf<BackupCategory?>(null) }
    var results by remember { mutableStateOf<Map<BackupCategory, ChannelCheck>>(emptyMap()) }
    var savedNotice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }

    fun say(text: String, error: Boolean = false) { savedNotice = text; noticeIsError = error }
    suspend fun assign(category: BackupCategory, chatId: Long, label: String) {
        settings.setChannel(category, chatId)
        repository.repointCategory(category, chatId)
        results = results - category
        WorkScheduler.rescheduleTelegramAutoSync(context)
        say("${categoryLabel(category)} → $label • background Telegram sync enabled")
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Channel Configuration") }, navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") }
            })
        }
    ) { padding ->
        val map = channelMap
        if (map == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            item {
                Text(
                    "Connect AirDrive to your Telegram storage channels. AirDrive imports the existing history and also keeps reconciling connected channels in the background.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        testing = true
                        scope.launch {
                            try { results = repository.testAllChannels().toMap() }
                            catch (e: Exception) { say(e.message ?: "Could not test Telegram channels", true) }
                            finally { testing = false }
                        }
                    },
                    enabled = !testing && !importing && !syncState.running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (testing) "Testing…" else "Test all channels") }

                Button(
                    onClick = {
                        importing = true
                        say("Starting Telegram inventory…")
                        scope.launch {
                            try {
                                WorkScheduler.importTelegramChannelsAwait(context)
                                say("Telegram inventory queued. It will scan all connected channels and update AirDrive in the background.")
                            } catch (e: Exception) {
                                say(e.message ?: "Could not start Telegram inventory", true)
                            } finally {
                                importing = false
                            }
                        }
                    },
                    enabled = !syncState.running && !testing && !importing,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text(when {
                        syncState.running -> "Importing Telegram files…"
                        importing -> "Starting import…"
                        else -> "Import existing Telegram files"
                    })
                }

                if (syncState.running || syncState.filesFound > 0 || syncState.finishedAt > 0L) {
                    Spacer(Modifier.height(10.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(if (syncState.running) "Telegram inventory running in background" else "Telegram inventory report", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(syncState.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (syncState.running) {
                                Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("You can leave this screen. The import continues in the background.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                            }
                            if (!syncState.running && syncState.finishedAt > 0L) {
                                Spacer(Modifier.height(10.dp))
                                Text("Telegram: ${syncState.filesFound} files found", style = MaterialTheme.typography.bodySmall)
                                Text("Added to AirDrive: ${syncState.imported}", style = MaterialTheme.typography.bodySmall)
                                Text("Already indexed: ${syncState.alreadyIndexed}", style = MaterialTheme.typography.bodySmall)
                                Text("Manifest JSON: ${syncState.manifestEntries} files", style = MaterialTheme.typography.bodySmall)
                                if (syncState.failedChannels > 0) Text("Failed channels: ${syncState.failedChannels}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        working = BackupCategory.values().first()
                        scope.launch {
                            var made = 0
                            try {
                                for (category in BackupCategory.values()) {
                                    working = category
                                    if ((map.perCategory[category] ?: 0L) != 0L) continue
                                    val created = repository.createChannel("AirDrive ${categoryLabel(category)}")
                                    assign(category, created.chatId, created.title); made++
                                }
                                say(if (made == 0) "Every category already has a channel." else "Created $made channel(s). Background sync enabled.")
                            } catch (e: Exception) { say(e.message ?: "Telegram would not create the channel", true) }
                            finally { working = null }
                        }
                    },
                    enabled = working == null && !testing && !syncState.running && !importing,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Create channels for empty categories") }

                savedNotice?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = if (noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.height(16.dp))
            }
            items(BackupCategory.values().toList()) { category ->
                var text by remember(category, map.perCategory[category]) { mutableStateOf(map.perCategory[category]?.takeIf { it != 0L }?.toString() ?: "") }
                val check = results[category]; val rowBusy = working == category
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(categoryLabel(category), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("ID, @username or link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    val direct = TdClient.normalizeChannelId(text); working = category
                                    scope.launch {
                                        try {
                                            if (direct != null) { text = direct.toString(); assign(category, direct, direct.toString()) }
                                            else { val resolved = repository.resolveChatInput(text); text = resolved.chatId.toString(); assign(category, resolved.chatId, resolved.title) }
                                        } catch (e: Exception) { say("${categoryLabel(category)}: ${e.message ?: "could not open that chat"}", true) }
                                        finally { working = null }
                                    }
                                },
                                enabled = working == null && text.isNotBlank() && !syncState.running && !importing
                            ) { Text("Save") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = {
                                    working = category
                                    scope.launch {
                                        try {
                                            val created = repository.createChannel("AirDrive ${categoryLabel(category)}")
                                            text = created.chatId.toString()
                                            assign(category, created.chatId, created.title)
                                        } catch (e: Exception) {
                                            say(e.message ?: "Could not create the channel", true)
                                        } finally { working = null }
                                    }
                                },
                                enabled = working == null && !syncState.running && !importing
                            ) { Text("Create") }
                            Spacer(Modifier.width(8.dp))
                            if (rowBusy) CircularProgressIndicator(Modifier.size(18.dp)) else when (check) {
                                is ChannelCheck.Ok -> Text("✓ ${check.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                is ChannelCheck.Failed -> Text(check.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                null -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}
