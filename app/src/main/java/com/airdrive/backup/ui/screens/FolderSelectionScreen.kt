package com.airdrive.backup.ui.screens

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

private fun lastSegment(uriString: String): String =
    try {
        android.net.Uri.parse(uriString).lastPathSegment ?: uriString
    } catch (e: Exception) {
        uriString
    }

/**
 * The path fragment to keep out of the scan for a folder picked through SAF. Android hands back a
 * tree document id shaped "volume:relative/path" — "primary:Documents/Notes" for the phone itself,
 * a volume uuid for a card — which maps onto the real path the direct walk uses, so the rule
 * matches exactly the folder that was picked. Anything less predictable falls back to the folder's
 * own name, which is what the typed rules in Backup settings do as well.
 */
private fun excludeFragmentFor(uri: android.net.Uri): String {
    val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
    val volume = docId.substringBefore(':', "")
    val relative = docId.substringAfter(':', "").trim('/')
    val root = when {
        volume.isBlank() -> null
        volume.equals("primary", ignoreCase = true) -> android.os.Environment.getExternalStorageDirectory()?.absolutePath
        volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) -> "/storage/$volume"
        else -> null
    }
    val path = root?.let { if (relative.isBlank()) it else "$it/$relative" }
    return (path ?: uri.lastPathSegment?.substringAfter(':', "") ?: docId).trim().trimEnd('/')
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
    OnResumeEffect { hasAccess = StorageAccess.hasFullAccess(context) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                if (excluding) {
                    // No permission to take: a rule only has to name the folder, and the scanner is
                    // the thing that reads it.
                    val fragment = excludeFragmentFor(uri)
                    notice = when {
                        fragment.isBlank() -> "Android did not tell us which folder that was — type the name under Backup settings instead."
                        excludesEverything(fragment) -> "That is the whole storage root, and taking it out would leave nothing to back up. Pick a folder inside it instead."
                        else -> {
                            settings.addExcludedPath(fragment)
                            "Skipping anything under $fragment."
                        }
                    }
                } else {
                    /**
                     * Persisting the grant is a call into another process and it is allowed to
                     * refuse: the picker can hand back something that is not a tree uri (a document
                     * uri from an OEM file manager), a provider may offer no persistable grant at
                     * all, and re-picking a folder that is already listed can come back without the
                     * requested mode. takePersistableUriPermission throws SecurityException /
                     * IllegalArgumentException in those cases, and this callback runs on the main
                     * thread with nothing around it — which is how choosing a folder killed the app.
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
                                                context.contentResolver.releasePersistableUriPermission(
                                                    android.net.Uri.parse(value)
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
                        onClick = { notice = null; folderPicker.launch(null) },
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
