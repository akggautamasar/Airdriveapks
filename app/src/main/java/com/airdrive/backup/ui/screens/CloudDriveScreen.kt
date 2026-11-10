package com.airdrive.backup.ui.screens

import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.prefs.AppLockStore
import com.airdrive.backup.drive.CloudDrivePrefs
import com.airdrive.backup.drive.CloudDriveProvider
import com.airdrive.backup.util.Format

/**
 * Settings for the AirDrive cloud drive: AirDrive as a storage location inside the system
 * Files app, streaming everything that is backed up in Telegram without downloading it first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudDriveScreen(nav: NavHostController) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val countFlow = remember { dao.uploadedCountFlow() }
    val bytesFlow = remember { dao.uploadedBytesFlow() }
    val cloudOnlyFlow = remember { dao.cloudOnlyBytesFlow() }
    val fileCount by countFlow.collectAsState(initial = 0)
    val totalBytes by bytesFlow.collectAsState(initial = 0L)
    val cloudOnlyBytes by cloudOnlyFlow.collectAsState(initial = 0L)

    var enabled by remember { mutableStateOf(CloudDrivePrefs.isEnabled(context)) }
    var freeCache by remember { mutableStateOf(CloudDrivePrefs.freeCacheOnClose(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    val locked = remember { AppLockStore(context).isEnabled() }

    fun openInFiles() {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(CloudDriveProvider.rootUri(context), DocumentsContract.Root.MIME_TYPE_ITEM)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(view)
        } catch (_: Exception) {
            // Some Files apps ignore a root link; the system picker can still browse the drive.
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, CloudDriveProvider.rootDocumentUri(context))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(picker)
            } catch (_: Exception) {
                message = "Open your Files app and look for AirDrive in the menu."
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cloud drive") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Your backed-up files, right in your Files app. Open a video and it streams straight " +
                    "from Telegram \u2014 only the part you watch is fetched, and nothing is copied onto " +
                    "this phone first. Free up space on the phone and everything stays one tap away.",
                style = MaterialTheme.typography.bodyLarge
            )

            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("In your cloud drive", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("$fileCount files \u2022 ${Format.bytes(totalBytes)}")
                    Text(
                        "${Format.bytes(cloudOnlyBytes)} of that is no longer on this phone \u2014 it only lives in Telegram.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Show AirDrive in the Files app", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Adds an AirDrive location to Files and to every app's file picker.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                CloudDrivePrefs.setEnabled(context, it)
                                CloudDriveProvider.notifyRootsChanged(context)
                            }
                        )
                    }
                    if (locked) {
                        Text(
                            "App Lock is on, so the drive stays hidden. Otherwise the Files app would be a way " +
                                "around the lock. Turn App Lock off to use the cloud drive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Clear the streaming cache after use", fontWeight = FontWeight.SemiBold)
                            Text(
                                "A minute after the last app closes a streamed file, its temporary copy is deleted so " +
                                    "streaming leaves nothing behind.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = freeCache,
                            onCheckedChange = {
                                freeCache = it
                                CloudDrivePrefs.setFreeCacheOnClose(context, it)
                            }
                        )
                    }
                }
            }

            Button(
                onClick = { openInFiles() },
                enabled = enabled && !locked,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open in Files app") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            Text("How it works", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "\u2022 Files appear in folders by type \u2014 Photos, Videos, PDFs, Documents, Audio, Call recordings, Other.\n" +
                    "\u2022 Opening a file streams it; videos and audio can be scrubbed instantly.\n" +
                    "\u2022 Copy or share a file INTO AirDrive from any app and it joins the normal backup queue; the " +
                    "temporary copy is removed once it is safely in Telegram.\n" +
                    "\u2022 Search in the Files app searches your whole backup by name.\n" +
                    "\u2022 Deleting is not possible from the Files app. To remove something from Telegram use " +
                    "Deleted files in AirDrive, which always asks first.\n" +
                    "\u2022 You must be signed in to Telegram for streaming. Files still on this phone open instantly, even offline.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
