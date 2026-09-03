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
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.telegram.ChannelCheck
import com.airdrive.backup.telegram.TdClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelConfigScreen(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()
    val channelMap by settings.allChannels.collectAsState(initial = null)

    var testing by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf<BackupCategory?>(null) }
    var results by remember { mutableStateOf<Map<BackupCategory, ChannelCheck>>(emptyMap()) }
    var savedNotice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }

    fun say(text: String, error: Boolean = false) {
        savedNotice = text
        noticeIsError = error
    }

    /** Stores [chatId] for [category] and repoints anything already queued for it. */
    suspend fun assign(category: BackupCategory, chatId: Long, label: String) {
        settings.setChannel(category, chatId)
        // Rows queued before this edit still point at the old id.
        repository.repointCategory(category, chatId)
        results = results - category
        say("${categoryLabel(category)} → $label")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Channel Configuration") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val map = channelMap
        if (map == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            item {
                Text(
                    "Each category uploads to its own Telegram channel. Paste an ID, a @username, " +
                        "a t.me link or an invite link — or let AirDrive create the channel for " +
                        "you. “Chat not found” means the signed-in account is not a member, so " +
                        "test before running a backup.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        testing = true
                        scope.launch {
                            results = repository.testAllChannels().toMap()
                            testing = false
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (testing) "Testing…" else "Test all channels") }
                OutlinedButton(
                    onClick = {
                        working = BackupCategory.values().first()
                        scope.launch {
                            var made = 0
                            try {
                                for (category in BackupCategory.values()) {
                                    working = category
                                    if ((map.perCategory[category] ?: 0L) != 0L) continue
                                    val created = repository.createChannel(
                                        "AirDrive ${categoryLabel(category)}"
                                    )
                                    assign(category, created.chatId, created.title)
                                    made++
                                }
                                say(
                                    if (made == 0) "Every category already has a channel."
                                    else "Created $made channel(s)."
                                )
                            } catch (e: Exception) {
                                say(e.message ?: "Telegram would not create the channel", true)
                            } finally {
                                working = null
                            }
                        }
                    },
                    enabled = working == null && !testing,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Create channels for the empty ones") }
                savedNotice?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (noticeIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            items(BackupCategory.values().toList()) { category ->
                var text by remember(category, map.perCategory[category]) {
                    mutableStateOf(map.perCategory[category]?.takeIf { it != 0L }?.toString() ?: "")
                }
                val check = results[category]
                val rowBusy = working == category
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(categoryLabel(category), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("ID, @username or link") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    // A bare id still works offline; anything else has to be
                                    // resolved through Telegram before it can be saved.
                                    val direct = TdClient.normalizeChannelId(text)
                                    working = category
                                    scope.launch {
                                        try {
                                            if (direct != null) {
                                                text = direct.toString()
                                                assign(category, direct, direct.toString())
                                            } else {
                                                val resolved = repository.resolveChatInput(text)
                                                text = resolved.chatId.toString()
                                                assign(category, resolved.chatId, resolved.title)
                                            }
                                        } catch (e: Exception) {
                                            say(
                                                "${categoryLabel(category)}: " +
                                                    (e.message ?: "could not open that chat"),
                                                true
                                            )
                                        } finally {
                                            working = null
                                        }
                                    }
                                },
                                enabled = working == null && text.isNotBlank()
                            ) { Text("Save") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = {
                                    working = category
                                    scope.launch {
                                        try {
                                            val created = repository.createChannel(
                                                "AirDrive ${categoryLabel(category)}"
                                            )
                                            text = created.chatId.toString()
                                            assign(category, created.chatId, created.title)
                                        } catch (e: Exception) {
                                            say(e.message ?: "Could not create the channel", true)
                                        } finally {
                                            working = null
                                        }
                                    }
                                },
                                enabled = working == null
                            ) { Text("Create") }
                            Spacer(Modifier.width(8.dp))
                            if (rowBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            } else {
                                when (check) {
                                    is ChannelCheck.Ok -> Text(
                                        "✓ ${check.title}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    is ChannelCheck.Failed -> Text(
                                        check.reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    null -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
