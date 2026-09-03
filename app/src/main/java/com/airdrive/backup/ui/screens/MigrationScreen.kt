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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.backup.ManifestSync
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.data.repo.MigrationState
import com.airdrive.backup.data.repo.RestoreState
import com.airdrive.backup.util.Format
import com.airdrive.backup.work.WorkScheduler
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Moving to a new phone, with no computer in the loop.
 *
 * The flow the screen walks through is: sign in to Telegram (already done by the time anyone gets
 * here), find the previous device's backup index, choose which categories to pull down, then let it
 * run. Files land in Downloads/AirDrive/<Category>/.
 *
 * Everything about it is resumable. Each file is marked the moment it arrives, so a migration that
 * is cancelled, killed, or interrupted by a flat battery continues from the first file that never
 * made it rather than starting the whole 40 GB again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationScreen(nav: NavHostController) {
    val context = LocalContext.current
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()

    val totals by remember { repository.restorableTotalsFlow() }
        .collectAsState(initial = emptyList())
    val restoredCount by remember { repository.restoredCountFlow() }.collectAsState(initial = 0)
    val migration by repository.migration.collectAsState()
    val currentFile by repository.restoreState.collectAsState()

    var selected by remember { mutableStateOf(emptySet<BackupCategory>()) }
    var skipRestored by remember { mutableStateOf(true) }
    var scanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    var confirmStartOver by remember { mutableStateOf(false) }
    var primed by remember { mutableStateOf(false) }

    // Everything gets ticked as soon as there is something to tick — "bring it all back" is the
    // whole point of a new phone. Only the first non-empty list primes it, so if the user then
    // unticks videos that choice is not undone by the next database emission.
    LaunchedEffect(totals) {
        if (!primed && totals.isNotEmpty()) {
            selected = totals.map { it.category }.toSet()
            primed = true
        }
    }

    val byCategory = remember(totals) { totals.associateBy { it.category } }
    val ordered = remember(byCategory) {
        BackupCategory.values().filter { byCategory.containsKey(it) }
    }
    val selectedFiles = remember(selected, byCategory) {
        selected.sumOf { byCategory[it]?.count ?: 0 }
    }
    val selectedBytes = remember(selected, byCategory) {
        selected.sumOf { byCategory[it]?.bytes ?: 0L }
    }

    // With "skip already restored" off the picker's own numbers undercount, because the totals flow
    // only knows about files this phone has never pulled down. Asking the queue keeps the figure on
    // the button honest in both modes; until it answers, the picker's number is the better guess.
    var plannedFiles by remember { mutableStateOf(selectedFiles) }
    LaunchedEffect(selected, skipRestored, restoredCount) {
        plannedFiles = if (selected.isEmpty()) 0
        else runCatching { repository.migrationQueueSize(selected, skipRestored) }
            .getOrDefault(selectedFiles)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Restore from old device") },
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
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Everything the old phone backed up is still in your Telegram account. Pick what " +
                    "you want on this one and AirDrive pulls it down — no computer, no cable.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            ScanCard(
                hasIndex = totals.isNotEmpty(),
                scanning = scanning,
                message = scanMessage,
                onScan = {
                    scanning = true
                    scanMessage = null
                    scope.launch {
                        val result = runCatching { repository.restoreManifestForced() }
                        scanning = false
                        scanMessage = result.fold(
                            onSuccess = { scanResultText(it) },
                            onFailure = { "Could not read the backup index: ${it.message}" }
                        )
                    }
                }
            )
            Spacer(Modifier.height(16.dp))

            if (!migration.idle) {
                MigrationProgressCard(
                    state = migration,
                    fileState = currentFile,
                    onCancel = {
                        WorkScheduler.cancelMigration(context)
                        repository.markMigrationCancelled()
                    },
                    onDismiss = { repository.clearMigrationState() }
                )
                Spacer(Modifier.height(16.dp))
            }

            if (ordered.isEmpty()) {
                Text(
                    "No backup index yet. Tap “Scan Telegram” above — AirDrive looks for the file " +
                        "list the old phone pinned in your Saved Messages.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            Text("Choose what to restore", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Divider()

            ordered.forEach { category ->
                val row = byCategory.getValue(category)
                CategoryPickRow(
                    label = categoryLabel(category),
                    files = row.count,
                    bytes = row.bytes,
                    checked = category in selected,
                    enabled = !migration.running,
                    onToggle = {
                        selected = if (category in selected) selected - category
                        else selected + category
                    }
                )
                Divider()
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { selected = ordered.toSet() },
                    enabled = !migration.running && selected.size < ordered.size
                ) { Text("Select all") }
                TextButton(
                    onClick = { selected = emptySet() },
                    enabled = !migration.running && selected.isNotEmpty()
                ) { Text("Clear") }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Skip files already restored", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Leave this on to continue an interrupted migration. Turn it off to pull " +
                            "everything down again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = skipRestored,
                    onCheckedChange = { skipRestored = it },
                    enabled = !migration.running
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    selected.isEmpty() -> "Nothing selected."
                    skipRestored ->
                        "${Format.count(plannedFiles)} files • ${formatBytes(selectedBytes)} to restore"
                    else ->
                        "${Format.count(plannedFiles)} files to restore, including the ones already " +
                            "pulled down once"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    // The state flips immediately so the button locks and the card appears; the
                    // worker takes over as soon as WorkManager starts it.
                    repository.markMigrationQueued()
                    WorkScheduler.startMigration(context, selected, skipRestored)
                },
                enabled = selected.isNotEmpty() && !migration.running,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (migration.running) "Restoring…" else "Start restore") }

            Spacer(Modifier.height(12.dp))
            Text(
                "Files arrive in Downloads/AirDrive, one folder per category. Nothing on this phone " +
                    "is overwritten — a name that already exists gets a number added. The restore " +
                    "keeps going while the app is closed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (restoredCount > 0) {
                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "${Format.count(restoredCount)} files have been restored onto this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { confirmStartOver = true },
                    enabled = !migration.running
                ) { Text("Start over") }
            }
            Spacer(Modifier.height(24.dp))

            if (confirmStartOver) {
                AlertDialog(
                    onDismissRequest = { confirmStartOver = false },
                    title = { Text("Start over?") },
                    text = {
                        Text(
                            "AirDrive will forget which files it has already pulled down, so the " +
                                "next restore fetches everything again. No file is deleted, and " +
                                "the copies already on this phone stay where they are."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmStartOver = false
                            scope.launch { repository.clearRestoreMarks(null) }
                        }) { Text("Start over") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmStartOver = false }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}

/**
 * Finding the old phone's backup index. This is the "Scan backup" step: the previous install pinned
 * a compressed file list in Saved Messages, and reading it is what turns an empty new install into
 * a phone that knows about 9,850 files it has never seen.
 */
@Composable
private fun ScanCard(
    hasIndex: Boolean,
    scanning: Boolean,
    message: String?,
    onScan: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (hasIndex) "Backup index found" else "Find the old backup",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (hasIndex) {
                    "Scan again if the old phone has backed up since this list was read."
                } else {
                    "AirDrive reads the file list the old phone saved to your Telegram account."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onScan, enabled = !scanning) {
                    Text(if (hasIndex) "Scan again" else "Scan Telegram")
                }
                if (scanning) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun CategoryPickRow(
    label: String,
    files: Int,
    bytes: Long,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${Format.count(files)} files • ${formatBytes(bytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Two bars: the outer one is the migration, the inner one the file currently coming down. Both are
 * needed — with 4 GB videos in the queue the outer bar can sit still for minutes, which reads as a
 * hang unless something visibly moves underneath it.
 */
@Composable
private fun MigrationProgressCard(
    state: MigrationState,
    fileState: RestoreState?,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when {
                    state.error != null -> "Migration stopped"
                    state.cancelled -> "Migration cancelled"
                    state.finished -> "Migration complete"
                    state.queued -> "Restore queued"
                    else -> "Restoring…"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))

            if (state.queued) {
                Text(
                    "Waiting to start. If your backup settings say Wi-Fi only, this begins the " +
                        "moment Wi-Fi is back — you can close the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }

            if (state.filesTotal > 0) {
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "${Format.count(state.filesDone)} of ${Format.count(state.filesTotal)} files" +
                        " • ${formatBytes(state.bytesDone)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.running && !state.queued) {
                val name = state.currentFile
                if (name != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    state.currentCategory?.let { category ->
                        Text(
                            categoryLabel(category),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (fileState != null && fileState.running) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { fileState.fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Reading the backup list…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.filesFailed > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${Format.count(state.filesFailed)} files could not be restored. They are still " +
                        "in Telegram — try again later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            state.error?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (state.cancelled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Start it again whenever you like — it picks up from the first file that did " +
                        "not arrive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            if (state.running) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            } else {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/** Turns a manifest scan result into one line the user can act on. */
private fun scanResultText(result: ManifestSync.RestoreResult): String = when (result) {
    is ManifestSync.RestoreResult.Restored -> {
        val date = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
            .format(Date(result.manifestDateMillis))
        "Found ${Format.count(result.fileCount)} backed-up files, indexed $date."
    }
    ManifestSync.RestoreResult.NoManifestFound ->
        "No backup list found in this Telegram account. Sign in with the account the old phone " +
            "used, or back up once from the old phone to create one."
    ManifestSync.RestoreResult.NotSignedIn -> "Not signed in to Telegram yet."
    ManifestSync.RestoreResult.NothingToDo -> "Already up to date."
    is ManifestSync.RestoreResult.Failed -> "Could not read the backup list: ${result.reason}"
}
