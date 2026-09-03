package com.airdrive.backup.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.CategoryCount
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.VerifyState
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.data.repo.CleanupResult
import com.airdrive.backup.util.Format
import kotlinx.coroutines.launch

/**
 * The storage cleanup assistant (wishlist item 9): "you can safely free 28.7 GB".
 *
 * One rule governs this whole screen. AirDrive deletes the phone's copy of a file only after
 * Telegram has confirmed, for that exact file, immediately before the delete, that it still holds
 * it. Nothing here trusts the database's word that an upload happened months ago, because the
 * local copy is the one the user can still see and getting this wrong loses their data.
 *
 * A cleanup pass therefore does the checking as it goes, and any file that fails the check is not
 * deleted but queued to be uploaded again. Files are freed one at a time and each is committed on
 * its own, so leaving the screen part-way through is safe: what was freed stays freed, the rest is
 * simply still there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupScreen(nav: NavHostController) {
    val context = LocalContext.current
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()

    var category by remember { mutableStateOf<BackupCategory?>(null) }
    var verifiedOnly by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var confirming by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<CleanupResult?>(null) }

    val totals by remember { repository.cleanupTotalsFlow() }.collectAsState(initial = emptyList())
    val freedBytes by remember { repository.freedBytesFlow() }.collectAsState(initial = 0L)
    val candidates by remember(category, verifiedOnly) {
        repository.cleanupCandidatesFlow(category, verifiedOnly, CLEANUP_PAGE)
    }.collectAsState(initial = emptyList())

    val reclaimable = remember(totals) { totals.sumOf { it.bytes } }
    val picked = remember(selected, candidates) { candidates.filter { it.id in selected } }
    val pickedBytes = remember(picked) { picked.sumOf { it.sizeBytes } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage cleanup") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            HeaderCard(reclaimable = reclaimable, freedBytes = freedBytes, totals = totals)

            CategoryFilterRow(
                selected = category,
                available = totals.map { it.category },
                enabled = !running,
                onSelect = { category = it; selected = emptySet() }
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Only files already verified", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Stricter: limits the list to files AirDrive has re-checked in Telegram " +
                            "since uploading them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = verifiedOnly,
                    onCheckedChange = { verifiedOnly = it; selected = emptySet() },
                    enabled = !running
                )
            }

            result?.let { outcome ->
                ResultCard(outcome) { result = null }
            }

            if (running) {
                RunningCard(done = done, total = total)
            }

            Divider()

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (picked.isEmpty()) "${Format.count(candidates.size)} file(s) listed"
                    else "${Format.count(picked.size)} selected • ${Format.bytes(pickedBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { selected = candidates.map { it.id }.toSet() },
                    enabled = !running && picked.size < candidates.size
                ) { Text("Select all") }
                TextButton(
                    onClick = { selected = emptySet() },
                    enabled = !running && selected.isNotEmpty()
                ) { Text("Clear") }
            }

            Button(
                onClick = { confirming = true },
                enabled = !running && picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Text(
                    if (picked.isEmpty()) "Free up space"
                    else "Free ${Format.bytes(pickedBytes)} " +
                        "(${Format.count(picked.size)} file(s))"
                )
            }

            Spacer(Modifier.height(8.dp))

            if (candidates.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (verifiedOnly) {
                            "Nothing verified here yet. Turn the switch off to include files " +
                                "AirDrive will check in Telegram as it frees them."
                        } else {
                            "Nothing to free here. A file is listed once it has been uploaded " +
                                "and is still taking up room on this phone."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
                return@Column
            }

            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(candidates, key = { it.id }) { record: FileRecord ->
                    CandidateRow(
                        record = record,
                        checked = record.id in selected,
                        enabled = !running,
                        onToggle = {
                            selected =
                                if (record.id in selected) selected - record.id
                                else selected + record.id
                        }
                    )
                    Divider()
                }
                // The list is capped, so say so rather than implying this is everything: the
                // per-category totals in the header are the real figure.
                if (candidates.size >= CLEANUP_PAGE) {
                    item {
                        Text(
                            "Showing the $CLEANUP_PAGE largest. Free these and the next ones " +
                                "take their place.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }

    if (confirming) {
        ConfirmDialog(
            files = picked.size,
            bytes = pickedBytes,
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                val batch = picked
                running = true
                done = 0
                total = batch.size
                result = null
                scope.launch {
                    val outcome = repository.freeLocalCopies(batch) { finished, count ->
                        done = finished
                        total = count
                    }
                    result = outcome
                    running = false
                    selected = emptySet()
                }
            }
        )
    }
}

/**
 * The list is capped on purpose. This screen is a hand-picking exercise, and the header's totals —
 * which count every eligible file, not just the listed ones — carry the "you could free 28.7 GB"
 * number. Kept in step with the flow call above by being passed to it explicitly.
 */
private const val CLEANUP_PAGE = 300

/** The wishlist's "You can safely free: Photos 12.4 GB, Videos 28.7 GB, Downloads 4.1 GB". */
@Composable
private fun HeaderCard(reclaimable: Long, freedBytes: Long, totals: List<CategoryCount>) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("You can safely free", style = MaterialTheme.typography.labelLarge)
            Text(
                Format.bytes(reclaimable),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Every file counted here is already in Telegram. AirDrive re-checks each one " +
                    "against Telegram at the moment it deletes it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (totals.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                for (row in totals.sortedByDescending { it.bytes }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${categoryLabel(row.category)} • ${Format.count(row.count)} file(s)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            Format.bytes(row.bytes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            if (freedBytes > 0) {
                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "${Format.bytes(freedBytes)} freed so far — those files are still in Telegram " +
                        "and can be restored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Only categories with something to free get a chip: a filter that can only ever show an empty list
 * is a dead end. Ordered by the enum rather than by size so the chips do not reshuffle under the
 * user's finger as files are freed, and a category that empties out while it is the active filter
 * keeps its chip so there is still something to tap off.
 */
@Composable
private fun CategoryFilterRow(
    selected: BackupCategory?,
    available: List<BackupCategory>,
    enabled: Boolean,
    onSelect: (BackupCategory?) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            enabled = enabled,
            label = { Text("All") }
        )
        for (category in BackupCategory.values()) {
            if (category !in available && category != selected) continue
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(if (selected == category) null else category) },
                enabled = enabled,
                label = { Text(categoryLabel(category)) }
            )
        }
    }
}

/**
 * Progress while a pass runs. The count is files *decided*, not files deleted — most of the time
 * per file goes on asking Telegram whether it still has it, and a file that fails that check is
 * counted here too even though nothing was removed.
 */
@Composable
private fun RunningCard(done: Int, total: Int) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Freeing space…", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "${Format.count(done)}/${Format.count(total)} checked against Telegram",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { done.toFloat() / total.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * What the pass did, in the user's terms. The requeued count gets the longest explanation because it
 * is the surprising outcome: the user asked to free space and AirDrive scheduled an upload instead.
 */
@Composable
private fun ResultCard(result: CleanupResult, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when {
                    result.stoppedReason != null -> "Cleanup stopped early"
                    result.nothingHappened -> "Nothing to remove"
                    result.freedFiles == 0 -> "Nothing was deleted"
                    result.failed > 0 || result.queuedForRepair > 0 -> "Freed some of it"
                    else -> "Space freed"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))

            if (result.freedFiles > 0) {
                Text(
                    "${Format.bytes(result.freedBytes)} freed • " +
                        "${Format.count(result.freedFiles)} file(s) removed from this phone",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "They are still in Telegram. Restore any of them from the backup list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (result.nothingHappened) {
                Text(
                    "Those files had already left this phone, so there was nothing to delete.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (result.queuedForRepair > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "${Format.count(result.queuedForRepair)} file(s) were kept, not deleted: " +
                        "Telegram no longer had a matching copy, so they are queued to upload " +
                        "again. Run a backup, then come back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (result.failed > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "${Format.count(result.failed)} file(s) would not delete. Android usually " +
                        "blocks this for files another app owns.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            result.stoppedReason?.let { reason ->
                Spacer(Modifier.height(10.dp))
                Text(
                    "$reason. Nothing was deleted without being checked first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/**
 * The gate the wishlist asks for: local copies go only after the user has been told exactly what is
 * being deleted and what is being kept. The confirm button names the action rather than saying OK,
 * and is coloured as a destructive one.
 */
@Composable
private fun ConfirmDialog(
    files: Int,
    bytes: Long,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${Format.count(files)} file(s) from this phone?") },
        text = {
            Column {
                Text(
                    "This frees about ${Format.bytes(bytes)}. The Telegram copies stay exactly " +
                        "where they are, so you can restore any of these files later."
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "AirDrive asks Telegram about each file in the moment before deleting it. If " +
                        "a copy is missing, the wrong size, or simply cannot be checked, that " +
                        "file is left on the phone and queued to upload again instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Free the space", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep them") }
        }
    )
}

/**
 * One candidate. The whole row toggles, not just the checkbox — these rows get tapped in long runs.
 * The third line says how much is known about the Telegram copy *before* the pass runs; it is not a
 * promise, because every file is checked again at delete time whatever it says here.
 */
@Composable
private fun CandidateRow(
    record: FileRecord,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
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
                verifyLine(record.verifyState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Wording for that third line. Deliberately never says "safe" on the strength of the record alone. */
private fun verifyLine(state: VerifyState): String = when (state) {
    VerifyState.VERIFIED -> "Confirmed in Telegram"
    VerifyState.UNVERIFIED -> "Uploaded — re-checked before deleting"
    VerifyState.MISSING_REMOTE -> "Was missing in Telegram last check — will be uploaded again"
    VerifyState.SIZE_MISMATCH -> "Sizes did not match last check — will be uploaded again"
    VerifyState.UNCHECKABLE -> "Last check could not complete — re-checked before deleting"
}
