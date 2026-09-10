package com.airdrive.backup.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.LocalState
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.Format
import com.airdrive.backup.work.WorkScheduler
import java.util.Locale

@Composable
fun FilesHubActions(record: FileRecord, nav: NavHostController) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Actions") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Preview") }, leadingIcon = { Icon(Icons.Default.Visibility, null) }, onClick = { menu = false; nav.navigate("${Routes.FILE_VIEWER}/${record.id}") })
            DropdownMenuItem(text = { Text("Upload now") }, leadingIcon = { Icon(Icons.Default.CloudUpload, null) }, onClick = { menu = false; WorkScheduler.runNowCategory(context, record.category) })
            DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                menu = false
                if (record.localState == LocalState.PRESENT) {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = mimeForFile(record.displayName)
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(record.uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share ${record.displayName}"))
                } else Toast.makeText(context, "This file is cloud-only. Open Preview to view it.", Toast.LENGTH_SHORT).show()
            })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("File details") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { menu = false; details = true })
        }
    }
    if (details) {
        AlertDialog(
            onDismissRequest = { details = false },
            icon = { Icon(Icons.Default.Info, null) },
            title = { Text(record.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailLine("Size", Format.bytes(record.sizeBytes))
                DetailLine("Type", record.category.name.lowercase(Locale.getDefault()).replace('_', ' '))
                DetailLine("Backup status", record.status.name.lowercase(Locale.getDefault()).replace('_', ' '))
                DetailLine("Local copy", record.localState.name.lowercase(Locale.getDefault()))
                DetailLine("Revision", "v${record.revision}")
                record.telegramMessageId?.let { DetailLine("Telegram message", it.toString()) }
            } },
            confirmButton = { TextButton(onClick = { details = false }) { Text("Done") } }
        )
    }
}

@Composable private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun mimeForFile(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.getDefault())) {
    "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"
    "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
    "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "wav" -> "audio/wav"
    "pdf" -> "application/pdf"; "txt" -> "text/plain"; else -> "application/octet-stream"
}
