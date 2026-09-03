package com.airdrive.backup.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.prefs.DestinationMode
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.telegram.ChannelCheck
import com.airdrive.backup.ui.nav.Routes
import kotlinx.coroutines.launch

/**
 * Where backups land. Saved Messages is the zero-setup choice — every Telegram account already
 * has it — while one-chat and per-category exist for people who want their files sorted. The
 * per-category mode is the original AirDrive behaviour and is untouched; it just lives behind a
 * radio button now instead of being the only option.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationScreen(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()

    val mode by settings.destinationMode.collectAsState(initial = null)
    val singleChatId by settings.singleChatId.collectAsState(initial = 0L)

    var chatInput by remember { mutableStateOf("") }
    var channelTitle by remember { mutableStateOf("AirDrive Backup") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ChannelCheck?>(null) }

    fun say(text: String, error: Boolean = false) {
        notice = text
        isError = error
    }

    /** Resolves whatever the user pasted and stores it as the single-chat destination. */
    fun useChat(raw: String) {
        busy = true
        testResult = null
        scope.launch {
            try {
                val resolved = repository.resolveChatInput(raw)
                settings.setSingleChatId(resolved.chatId)
                settings.setDestinationMode(DestinationMode.SINGLE_CHAT)
                chatInput = ""
                say("Backups will go to “${resolved.title}”.")
            } catch (e: Exception) {
                say(e.message ?: "Could not open that chat", error = true)
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup destination") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val current = mode
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Pick one. You can switch at any time — files already uploaded stay where they are.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            DestinationOption(
                title = "Saved Messages",
                subtitle = "No setup at all. Everything goes to your own Telegram Saved Messages.",
                option = DestinationMode.SAVED_MESSAGES,
                selected = current
            ) {
                scope.launch { settings.setDestinationMode(it); testResult = null }
                say("Saved Messages it is — nothing else to configure.")
            }
            DestinationOption(
                title = "One channel or chat",
                subtitle = "Everything in a single place you choose.",
                option = DestinationMode.SINGLE_CHAT,
                selected = current
            ) {
                scope.launch { settings.setDestinationMode(it); testResult = null }
            }
            DestinationOption(
                title = "A channel per file type",
                subtitle = "Photos, videos, documents and the rest each get their own channel.",
                option = DestinationMode.PER_CATEGORY,
                selected = current
            ) {
                scope.launch { settings.setDestinationMode(it); testResult = null }
            }

            if (current == DestinationMode.SINGLE_CHAT) {
                Spacer(Modifier.height(16.dp))
                Divider()
                Spacer(Modifier.height(12.dp))
                Text("Which chat", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Paste anything Telegram gives you: a channel ID like -1001234567890, a " +
                        "@username, a t.me link, or a private invite link. You have to be a " +
                        "member, and for channels an admin who can post.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = chatInput,
                    onValueChange = { chatInput = it },
                    label = { Text("ID, @username or link") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { useChat(chatInput) },
                    enabled = !busy && chatInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Checking…" else "Use this chat") }

                Spacer(Modifier.height(20.dp))
                Text("Or let AirDrive make one", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Creates a new private channel on your account and points backups at it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = channelTitle,
                    onValueChange = { channelTitle = it.take(128) },
                    label = { Text("Channel name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        busy = true
                        testResult = null
                        scope.launch {
                            try {
                                val created = repository.createChannel(channelTitle)
                                settings.setSingleChatId(created.chatId)
                                settings.setDestinationMode(DestinationMode.SINGLE_CHAT)
                                say("Created “${created.title}” and selected it.")
                            } catch (e: Exception) {
                                say(e.message ?: "Telegram would not create the channel", error = true)
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && channelTitle.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Create a channel for me") }

                Spacer(Modifier.height(12.dp))
                Text(
                    if (singleChatId == 0L) "No chat chosen yet."
                    else "Current destination: chat $singleChatId",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (singleChatId == 0L) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (current == DestinationMode.PER_CATEGORY) {
                Spacer(Modifier.height(16.dp))
                Divider()
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { nav.navigate(Routes.CHANNEL_CONFIG) }) {
                    Text("Set up the seven channels")
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    busy = true
                    notice = null
                    scope.launch {
                        testResult = repository.testDestination()
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Testing…" else "Test destination") }

            testResult?.let { result ->
                Spacer(Modifier.height(12.dp))
                when (result) {
                    is ChannelCheck.Ok -> Text(
                        "✓ ${result.title}",
                        color = MaterialTheme.colorScheme.primary
                    )
                    is ChannelCheck.Failed -> Text(
                        result.reason,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            notice?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DestinationOption(
    title: String,
    subtitle: String,
    option: DestinationMode,
    selected: DestinationMode,
    onPick: (DestinationMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = option == selected, onClick = { onPick(option) })
        Spacer(Modifier.width(4.dp))
        Column(Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
