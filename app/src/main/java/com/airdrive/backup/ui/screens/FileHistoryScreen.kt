package com.airdrive.backup.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.FileVersion
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.util.Format
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File version history (wishlist item 3) — the screen that makes AirDrive a lightweight version
 * control system for a phone.
 *
 * The insight it is built on: Telegram never deletes anything on its own. When a file changes and
 * gets uploaded again, the *older* copy is still sitting in the channel, perfectly intact — the only
 * thing lost was AirDrive's pointer to it, because the record's message id was overwritten. The
 * file_versions table keeps those pointers, and this screen spends them.
 *
 * Nothing here overwrites anything. Restoring an old copy writes it into Downloads/AirDrive under a
 * name carrying its revision, so both versions sit side by side and the user decides which to keep.
 * That is deliberate: the whole reason to want v2 of a file is that you are not certain about v3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileHistoryScreen(nav: NavHostController) {
    val context = LocalContext.current
    val repository = remember { BackupRepository.get(context) }

    var selected by remember { mutableStateOf<FileRecord?>(null) }

    val files by remember { repository.versionedFilesFlow() }.collectAsState(initial = emptyList())
    val counts by remember { repository.versionCountsFlow() }.collectAsState(initial = emptyList())
    val countByRecord = remember(counts) { counts.associate { it.recordId to it.versions } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected == null) "File history" else "Versions") },
                navigationIcon = {
                    // Back out of one file's versions first, then out of the screen — the list and
                    // the version detail share a destination, so the system back arrow has to too.
                    IconButton(
                        onClick = {
                            val open = selected
                            if (open != null) {
                                selected = null
                            } else {
                                nav.popBackStack()
                            }
                        }
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val record = selected
            if (record != null) {
                VersionList(record = record, repository = repository)
                return@Column
            }

            Text(
                "Files AirDrive has uploaded more than once. Every upload was kept, so an earlier " +
                    "copy can still be pulled back out of Telegram.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Divider()

            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No file has been uploaded twice yet. A history appears the first time a " +
                            "file you have already backed up changes and gets sent again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(files, key = { it.id }) { file ->
                    HistoryFileRow(
                        record = file,
                        versions = countByRecord[file.id] ?: 0
                    ) { selected = file }
                    Divider()
                }
            }
        }
    }
}

/** One file in the list, with how many copies of it are reachable. */
@Composable
private fun HistoryFileRow(record: FileRecord, versions: Int, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            record.displayName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${categoryLabel(record.category)} • ${Format.bytes(record.sizeBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            if (versions > 0) "${Format.count(versions)} versions kept" else "Versions kept",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * The versions of one file, newest first, each with a button that fetches it. Only one restore runs
 * at a time — they all go through the same TDLib download path and the same progress state, so two
 * at once would fight over both.
 */
@Composable
private fun VersionList(record: FileRecord, repository: BackupRepository) {
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val versions by remember(record.id) { repository.versionsFlow(record.id) }
        .collectAsState(initial = emptyList())

    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            record.displayName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${categoryLabel(record.category)} • now ${Format.bytes(record.sizeBytes)} • " +
                "revision ${record.revision}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Restoring a version saves it to Downloads/AirDrive with its number in the name — " +
                "the copy on your phone is left exactly as it is.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (busy) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    }

    message?.let { line ->
        Text(
            line,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }

    error?.let { line ->
        Text(
            line,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }

    Divider()

    LazyColumn(Modifier.fillMaxSize()) {
        items(versions, key = { it.id }) { version ->
            VersionRow(
                version = version,
                currentName = record.displayName,
                isCurrent = version.revision >= record.revision,
                busy = busy,
                fmt = fmt,
                onRestore = {
                    busy = true
                    message = null
                    error = null
                    scope.launch {
                        runCatching { repository.restoreVersion(record, version) }
                            .onSuccess {
                                message = "Saved as ${it.name} in Downloads/AirDrive."
                            }
                            .onFailure { error = it.message?.take(200) ?: "Could not fetch it" }
                        busy = false
                    }
                }
            )
            Divider()
        }

        item {
            Text(
                "Only uploads AirDrive recorded appear here. Copies sent before this version of " +
                    "the app may still be in Telegram, but there is no note of where.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

/**
 * One recorded upload. The size and the modified date are the version's own, not the record's, which
 * is the point — "the 2.1 MB one from Tuesday" is how people actually identify the copy they want.
 */
@Composable
private fun VersionRow(
    version: FileVersion,
    currentName: String,
    isCurrent: Boolean,
    busy: Boolean,
    fmt: SimpleDateFormat,
    onRestore: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Version ${version.revision}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            if (isCurrent) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "current",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            "${Format.bytes(version.sizeBytes)} • uploaded ${fmt.format(Date(version.uploadedAtMillis))}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "File dated ${fmt.format(Date(version.modifiedAtMillis))}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // A rename between uploads is worth pointing out, and only then: the user knows this file by
        // its current name, and this copy went into Telegram under a different one.
        if (version.displayName.isNotBlank() && version.displayName != currentName) {
            Text(
                "Sent as ${version.displayName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(6.dp))
        if (version.telegramMessageId == null) {
            Text(
                "This upload has no message recorded, so it cannot be fetched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            TextButton(onClick = onRestore, enabled = !busy) {
                Text(if (isCurrent) "Download this copy" else "Restore this version")
            }
        }
    }
}
