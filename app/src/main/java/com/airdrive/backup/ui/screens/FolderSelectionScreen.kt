package com.airdrive.backup.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun lastSegment(uriString: String): String =
    try {
        android.net.Uri.parse(uriString).lastPathSegment ?: uriString
    } catch (e: Exception) {
        uriString
    }

/** True when a rule would keep out the whole phone rather than one folder. */
private fun excludesEverything(fragment: String): Boolean {
    val cleaned = fragment.trim().trimEnd('/').lowercase()
    if (cleaned.length < 3) return true
    if (cleaned == "/storage" || cleaned == "/sdcard" || cleaned == "/emulated" || cleaned == "/") return true
    if (cleaned.matches(Regex("/storage(/[^/]+)?"))) return true
    val primary = android.os.Environment.getExternalStorageDirectory()?.absolutePath?.lowercase()
    return primary != null && cleaned == primary.trimEnd('/')
}

/**
 * "Specific folders" screen, in two moods: the list of SAF trees AirDrive is allowed to read
 * (`excluding = false`, used during onboarding), or the list of folder rules that keep files out of
 * the backup altogether (`excluding = true`, reached from "Excluded folders").
 *
 * Optional now. With "All files access" granted AirDrive walks the whole phone and never needs a
 * picked folder, so nothing on this screen blocks Continue — it exists for devices where that
 * permission is unavailable or declined.
 *
 * Excluding uses AirDrive's own folder list rather than the system picker: a rule only has to name a
 * path, and the picker is the part of this flow that was killing the app.
 */
@Composable
fun FolderSelectionScreen(nav: NavHostController, excluding: Boolean = false) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    val authorizedUris by settings.authorizedTreeUris.collectAsState(initial = emptySet())
    val excluded by settings.excludedPaths.collectAsState(initial = emptySet())
    val wholeDevice by settings.scanWholeDevice.collectAsState(initial = true)
    var hasAccess by remember { mutableStateOf(StorageAccess.hasFullAccess(context)) }
    var notice by remember { mutableStateOf<String?>(null) }
    var browsing by remember { mutableStateOf(false) }
    OnResumeEffect { hasAccess = StorageAccess.hasFullAccess(context) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                /**
                 * Persisting the grant is a call into another process and it is allowed to refuse:
                 * the picker can hand back something that is not a tree uri (a document uri from an
                 * OEM file manager), a provider may offer no persistable grant at all, and re-picking
                 * a folder that is already listed can come back without the requested mode.
                 * takePersistableUriPermission throws SecurityException / IllegalArgumentException in
                 * those cases, and this callback runs on the main thread with nothing around it —
                 * which is how choosing a folder killed the app.
                 */
                val folder = lastSegment(uri.toString())
                val persisted = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        true
                    }.getOrDefault(false)
                }
                settings.addAuthorizedTreeUri(uri.toString())
                notice = if (persisted) {
                    "Added $folder."
                } else {
                    "Added $folder, but Android would not keep access to it — backups may not be " +
                        "able to read it after a restart."
                }
            }
        }
    }

    if (excluding && browsing) {
        BackHandler { browsing = false }
        FolderBrowser(
            startDir = File(android.os.Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"),
            canList = hasAccess || !StorageAccess.grantedFromSettingsScreen,
            onDismiss = { browsing = false },
            onPick = { folder ->
                val path = folder.absolutePath
                scope.launch {
                    if (excludesEverything(path)) {
                        notice = "$path is the whole storage root — taking it out would leave nothing " +
                            "to back up. Open it and pick a folder inside instead."
                        browsing = false
                    } else {
                        settings.addExcludedPath(path)
                        notice = "$path is kept out of the backup from now on."
                        browsing = false
                    }
                }
            }
        )
        return
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text(
                if (excluding) "Excluded folders" else "Specific folders",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (excluding) {
                    "Pick a folder and AirDrive will keep it out of the backup. A rule is a path " +
                        "fragment, so anything whose path contains it is never scanned — including " +
                        "files that appear there later."
                } else if (wholeDevice && hasAccess) {
                    "Not needed: AirDrive is already scanning every folder on the phone. Anything " +
                        "you add here is simply included as well."
                } else {
                    "AirDrive will scan only the folders listed here."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(20.dp))
            Text(
                if (excluding) "Kept out of the backup" else "Authorized folders",
                style = MaterialTheme.typography.titleMedium
            )
            notice?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))

            val rows = if (excluding) excluded.toList() else authorizedUris.toList()
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(rows) { value ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (excluding) value else lastSegment(value),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            IconButton(onClick = {
                                scope.launch {
                                    if (excluding) {
                                        settings.removeExcludedPath(value)
                                        notice = "$value will be scanned again."
                                    } else {
                                        withContext(Dispatchers.IO) {
                                            // Android refuses to release a grant that was never
                                            // persisted, or whose provider has gone; that must not
                                            // leave a row the user cannot get rid of.
                                            runCatching {
                                                // Same mask as the take above: the public API only
                                                // has releasePersistableUriPermission(uri, modeFlags).
                                                context.contentResolver.releasePersistableUriPermission(
                                                    android.net.Uri.parse(value),
                                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                )
                                            }
                                        }
                                        settings.removeAuthorizedTreeUri(value)
                                        notice = "Removed ${lastSegment(value)}; AirDrive will not scan it any more."
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove")
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = {
                            notice = null
                            if (excluding) {
                                browsing = true
                            } else {
                                // Some devices resolve ACTION_OPEN_DOCUMENT_TREE to nothing at all —
                                // a disabled DocumentsUI, a cloned work profile. androidx then throws
                                // ActivityNotFoundException straight out of launch(), on the tap, which
                                // is the app closing for no visible reason. Whole-device access does
                                // not need this picker, so say that instead of dying.
                                try {
                                    folderPicker.launch(null)
                                } catch (e: Exception) {
                                    notice = "This device has no system folder picker. Grant All files " +
                                        "access under Storage access instead and AirDrive scans every " +
                                        "folder without being shown them."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) { Text(if (excluding) "+ Choose a folder to skip" else "+ Choose folders") }
                }
                if (rows.isEmpty()) {
                    item {
                        Text(
                            if (excluding) {
                                "Nothing excluded yet. Caches, thumbnails and app data folders are " +
                                    "always skipped, with or without a rule here."
                            } else if (wholeDevice && hasAccess) {
                                "Nothing listed, and that is fine — every folder on the phone is scanned anyway."
                            } else {
                                "No folders yet. AirDrive backs up nothing it has not been shown."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            Button(
                // Never disabled: requiring a folder here is what made onboarding a dead end.
                onClick = {
                    if (excluding) {
                        nav.popBackStack()
                    } else {
                        nav.navigate(Routes.READY)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (excluding) "Done" else "Continue") }
        }
    }
}

/**
 * AirDrive's own folder list, used for exclusions. A skip rule only has to name a path, so this
 * needs no system picker, no persistable grant and no second app being alive: it is just a
 * directory walk, which is the part of the old flow that could not throw hard enough to matter.
 *
 * Reads through the same All files access the scanner uses. Where that is missing, nothing lists,
 * and the caller is told to type the path instead of being shown an empty screen.
 */
@Composable
private fun FolderBrowser(
    startDir: File,
    canList: Boolean,
    onDismiss: () -> Unit,
    onPick: (File) -> Unit
) {
    var dir by remember { mutableStateOf(startDir) }
    var children by remember { mutableStateOf<List<File>?>(null) }
    var unreadable by remember { mutableStateOf(false) }

    LaunchedEffect(dir) {
        children = null
        val listing = withContext(Dispatchers.IO) {
            runCatching { dir.listFiles() }
        }
        unreadable = listing.isFailure || listing.getOrNull() == null
        children = listing.getOrNull()
            ?.filter { entry -> entry.isDirectory && !entry.name.startsWith(".") && entry.canRead() }
            ?.sortedBy { entry -> entry.name.lowercase() }
            ?.toList()
            ?: emptyList()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { dir.parentFile?.let { parent -> dir = parent } }) { Text("Up") }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Choose a folder to skip", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        dir.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            Button(
                onClick = { onPick(dir) },
                enabled = dir.parentFile != null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) { Text("Skip this folder") }
            Spacer(Modifier.height(10.dp))

            val folders = children
            when {
                folders == null -> Text(
                    "Reading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                folders.isEmpty() && unreadable -> Text(
                    if (canList) {
                        "Android would not let AirDrive read this folder. Grant All files access under " +
                            "Storage access, or type the path under Backup settings instead."
                    } else {
                        "AirDrive cannot list folders without All files access. Grant it under Storage " +
                            "access, or type the path under Backup settings instead."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                folders.isEmpty() -> Text(
                    "No folders inside this one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                else -> LazyColumn(modifier = Modifier.weight(1f)) {
                    items(folders) { child ->
                        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { dir = child }, modifier = Modifier.weight(1f)) {
                                    Text(child.name, maxLines = 1)
                                }
                                TextButton(onClick = { onPick(child) }) { Text("Skip") }
                            }
                        }
                    }
                }
            }

            Text(
                "Open a folder to go deeper, or Skip to keep that folder and everything inside it out " +
                    "of the backup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )
        }
    }
}
