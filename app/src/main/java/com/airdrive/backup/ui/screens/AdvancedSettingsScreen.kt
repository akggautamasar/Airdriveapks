package com.airdrive.backup.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.prefs.DEFAULT_CAPTION_TEMPLATE
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.prefs.UploadOrder
import com.airdrive.backup.data.repo.BackupRepository
import kotlinx.coroutines.launch

/**
 * The knobs that change *what* gets backed up and *how it is labelled*, kept off the main
 * settings screen so the common case stays short.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()

    val excluded by settings.excludedPaths.collectAsState(initial = emptySet())
    val maxSizeMb by settings.maxFileSizeMb.collectAsState(initial = 0L)
    val order by settings.uploadOrder.collectAsState(initial = UploadOrder.OLDEST_FIRST)
    val storedTemplate by settings.captionTemplate.collectAsState(initial = null)

    var newExclusion by remember { mutableStateOf("") }
    var sizeText by remember { mutableStateOf<String?>(null) }
    var template by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // Seed the editable fields once, then leave them alone so typing is not fought by the flow.
    LaunchedEffect(storedTemplate) {
        if (template == null) storedTemplate?.let { template = it }
    }
    LaunchedEffect(maxSizeMb) {
        if (sizeText == null) sizeText = if (maxSizeMb <= 0L) "" else maxSizeMb.toString()
    }

    fun say(text: String, error: Boolean = false) {
        notice = text
        isError = error
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan rules & captions") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Skip these folders", style = MaterialTheme.typography.titleMedium)
            Text(
                "Any file whose path contains one of these is never queued. Type a folder name " +
                    "like “WhatsApp Voice Notes” or a longer path fragment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newExclusion,
                    onValueChange = { newExclusion = it },
                    label = { Text("Folder or path fragment") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val fragment = newExclusion.trim()
                        if (fragment.length < 2) {
                            say("That is too short to be safe — it would skip almost everything", true)
                        } else {
                            scope.launch {
                                settings.addExcludedPath(fragment)
                                newExclusion = ""
                                say("Skipping anything under “$fragment”.")
                            }
                        }
                    },
                    enabled = newExclusion.isNotBlank()
                ) { Text("Add") }
            }

            if (excluded.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Nothing excluded yet. Caches, thumbnails and app data folders are always " +
                        "skipped regardless.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                for (fragment in excluded.sorted()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(fragment, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            scope.launch {
                                settings.removeExcludedPath(fragment)
                                say("“$fragment” will be scanned again.")
                            }
                        }) { Icon(Icons.Default.Close, contentDescription = "Remove $fragment") }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Text("Size limit", style = MaterialTheme.typography.titleMedium)
            Text(
                "Skip files larger than this. Leave empty for no limit — Telegram itself refuses " +
                    "anything over 4 GB, so those are always skipped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = sizeText.orEmpty(),
                    onValueChange = { sizeText = it.filter { c -> c.isDigit() }.take(7) },
                    label = { Text("Megabytes") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val mb = sizeText.orEmpty().trim().toLongOrNull() ?: 0L
                    scope.launch {
                        settings.setMaxFileSizeMb(mb)
                        say(if (mb <= 0L) "No size limit." else "Skipping files over $mb MB.")
                    }
                }) { Text("Save") }
            }

            Spacer(Modifier.height(20.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Text("Upload order", style = MaterialTheme.typography.titleMedium)
            OrderOption("Oldest files first", UploadOrder.OLDEST_FIRST, order) {
                scope.launch { settings.setUploadOrder(it) }
            }
            OrderOption("Newest files first", UploadOrder.NEWEST_FIRST, order) {
                scope.launch { settings.setUploadOrder(it) }
            }
            OrderOption("Smallest files first", UploadOrder.SMALLEST_FIRST, order) {
                scope.launch { settings.setUploadOrder(it) }
            }
            Text(
                "Smallest first empties the queue fastest; newest first gets today's photos up " +
                    "before anything else.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Text("Caption", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sent with every file. Placeholders: {name} {date} {size} {folder} {path} " +
                    "{category} {ext}. A line whose placeholders all come out empty is dropped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = template.orEmpty(),
                onValueChange = { template = it.take(900) },
                label = { Text("Caption template") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    scope.launch {
                        settings.setCaptionTemplate(template.orEmpty())
                        say("Caption saved.")
                    }
                }) { Text("Save caption") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = {
                    template = DEFAULT_CAPTION_TEMPLATE
                    scope.launch {
                        settings.setCaptionTemplate(DEFAULT_CAPTION_TEMPLATE)
                        say("Caption reset to the default.")
                    }
                }) { Text("Reset") }
            }

            Spacer(Modifier.height(20.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Text("Export", style = MaterialTheme.typography.titleMedium)
            Text(
                "Both files are written to Downloads/AirDrive so you can open or share them from " +
                    "any file manager.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val file = repository.exportManifest()
                            say("Saved ${file.name} to Downloads/AirDrive.")
                        } catch (e: Exception) {
                            say(e.message ?: "Could not write the manifest", true)
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Export a CSV of everything uploaded") }
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val file = repository.exportSettings()
                            say("Saved ${file.name} to Downloads/AirDrive.")
                        } catch (e: Exception) {
                            say(e.message ?: "Could not write the settings file", true)
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Export my settings") }

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
private fun OrderOption(
    label: String,
    option: UploadOrder,
    selected: UploadOrder,
    onPick: (UploadOrder) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = option == selected, onClick = { onPick(option) })
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
