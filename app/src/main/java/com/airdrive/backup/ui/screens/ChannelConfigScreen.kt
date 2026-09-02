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
import androidx.compose.ui.text.input.KeyboardType
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
    var results by remember { mutableStateOf<Map<BackupCategory, ChannelCheck>>(emptyMap()) }
    var savedNotice by remember { mutableStateOf<String?>(null) }

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
                    "Each category uploads to its own Telegram channel. “Chat not found” means the " +
                        "signed-in account is not a member of that channel, or the ID is wrong — " +
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
                savedNotice?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
            }
            items(BackupCategory.values().toList()) { category ->
                var text by remember(category, map.perCategory[category]) {
                    mutableStateOf(map.perCategory[category]?.toString() ?: "")
                }
                val check = results[category]
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(categoryLabel(category), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("Channel ID (e.g. -100xxxxxxxxxx)") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                // Accepts -100…, 100… or the bare supergroup id, so a copy-paste
                                // from Telegram's web URL still lands on a valid chat id.
                                val id = TdClient.normalizeChannelId(text)
                                if (id == null) {
                                    savedNotice = "${categoryLabel(category)}: not a valid channel ID"
                                } else {
                                    text = id.toString()
                                    scope.launch {
                                        settings.setChannel(category, id)
                                        // Rows queued before this edit still point at the old id.
                                        repository.repointCategory(category, id)
                                        results = results - category
                                        savedNotice = "${categoryLabel(category)} saved as $id"
                                    }
                                }
                            }) { Text("Save") }
                            Spacer(Modifier.width(12.dp))
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
