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
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.ui.nav.Routes
import kotlinx.coroutines.launch

private fun categoryLabel(c: BackupCategory) = when (c) {
    BackupCategory.PHOTOS -> "Photos"
    BackupCategory.VIDEOS -> "Videos"
    BackupCategory.PDFS -> "PDFs"
    BackupCategory.WORD_EXCEL -> "Documents"
    BackupCategory.AUDIO -> "Audio"
    BackupCategory.CALL_RECORDINGS -> "Call recordings"
    BackupCategory.OTHER_FILES -> "Other files"
}

private fun lastSegment(uriString: String): String =
    try {
        android.net.Uri.parse(uriString).lastPathSegment ?: uriString
    } catch (e: Exception) {
        uriString
    }

@Composable
fun FolderSelectionScreen(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    val authorizedUris by settings.authorizedTreeUris.collectAsState(initial = emptySet())
    val enabledCategories by settings.enabledCategories.collectAsState(initial = BackupCategory.values().toSet())

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            scope.launch { settings.addAuthorizedTreeUri(uri.toString()) }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("Choose what to back up", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            BackupCategory.values().forEach { category ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = category in enabledCategories,
                        onCheckedChange = { checked ->
                            val next = if (checked) enabledCategories + category else enabledCategories - category
                            scope.launch { settings.setEnabledCategories(next) }
                        }
                    )
                    Text(categoryLabel(category))
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Authorized folders", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(authorizedUris.toList()) { uriString ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                lastSegment(uriString),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            IconButton(onClick = { scope.launch { settings.removeAuthorizedTreeUri(uriString) } }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove")
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) { Text("+ Choose Folders") }
                }
            }

            Button(
                onClick = { nav.navigate(Routes.READY) },
                enabled = authorizedUris.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Continue") }
        }
    }
}
