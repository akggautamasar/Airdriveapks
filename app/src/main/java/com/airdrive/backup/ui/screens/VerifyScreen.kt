package com.airdrive.backup.ui.screens

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
import com.airdrive.backup.data.db.LocalState
import com.airdrive.backup.data.db.VerifyCount
import com.airdrive.backup.data.db.VerifyState
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.data.repo.VerifyResult
import com.airdrive.backup.util.Format
import kotlinx.coroutines.launch

/**
 * Backup verification (wishlist item 15). An upload that "succeeded" is, until something checks,
 * only a row in a database — this screen is where that row is turned into a fact by asking Telegram
 * whether it still holds the file the index says it does.
 *
 * A sweep repairs what it can as it goes: a file that is missing or the wrong size in Telegram, and
 * still present on the phone, goes straight back into the upload queue. That is why the problem list
 * below is usually short and made of the ones that cannot be repaired — no local copy left to send.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyScreen(nav: NavHostController) {
    val context = LocalContext.current
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()

    var onlyUnchecked by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<VerifyResult?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val breakdown by remember { repository.verifyBreakdownFlow() }.collectAsState(initial = emptyList())
    val problems by remember { repository.verifyProblemsFlow() }.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup verification") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            BreakdownCard(breakdown)

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Only files never checked", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Off, a sweep also re-checks the files it looked at longest ago.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = onlyUnchecked,
                    onCheckedChange = { onlyUnchecked = it },
                    enabled = !running
                )
            }

            Button(
                onClick = {
                    running = true
                    done = 0
                    total = 0
                    result = null
                    message = null
                    scope.launch {
                        val outcome = repository.verifyNow(onlyUnchecked) { finished, count ->
                            done = finished
                            total = count
                        }
                        result = outcome
                        running = false
                    }
                },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) { Text(if (running) "Checking…" else "Check now") }

            Text(
                "Each file is one request to Telegram, so a sweep takes minutes rather than " +
                    "seconds. It carries on from where the last one stopped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (running) {
                VerifyProgressCard(done = done, total = total)
            }

            result?.let { outcome ->
                VerifyResultCard(outcome) { result = null }
            }

            message?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Divider()

            Text(
                "Files that need you",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (problems.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing outstanding. Files a sweep can fix by itself are queued for " +
                            "upload straight away, so they never appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
                return@Column
            }

            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(problems, key = { it.id }) { record: FileRecord ->
                    ProblemRow(
                        record = record,
                        busy = running,
                        onRepair = {
                            scope.launch {
                                val queued = repository.repairFile(record)
                                message = if (queued) {
                                    "${record.displayName} is queued — run a backup to send it again."
                                } else {
                                    "${record.displayName} is no longer on this phone, so there is " +
                                        "nothing left to upload."
                                }
                            }
                        },
                        onForget = {
                            scope.launch {
                                repository.forgetRecord(record)
                                message = "Removed ${record.displayName} from the list."
                            }
                        }
                    )
                    Divider()
                }
            }
        }
    }
}

/**
 * How much of the backup has actually been confirmed. The bar is confirmed-against-uploaded rather
 * than a percentage of "checks passed": the interesting number is how much of the library has been
 * proven to exist, and a file nobody has looked at yet is not evidence of anything.
 */
@Composable
private fun BreakdownCard(breakdown: List<VerifyCount>) {
    val counts = remember(breakdown) { breakdown.associate { it.verifyState to it.count } }
    val total = remember(breakdown) { breakdown.sumOf { it.count } }
    val confirmed = counts[VerifyState.VERIFIED] ?: 0

    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Confirmed in Telegram", style = MaterialTheme.typography.labelLarge)
            Text(
                "${Format.count(confirmed)} of ${Format.count(total)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (total > 0) confirmed.toFloat() / total.toFloat() else 0f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            for (state in VerifyState.values()) {
                val count = counts[state] ?: 0
                if (count == 0 && state != VerifyState.UNVERIFIED) continue
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(verifyStateLabel(state), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        Format.count(count),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/** Plain names for the states, in the order the enum declares them. */
private fun verifyStateLabel(state: VerifyState): String = when (state) {
    VerifyState.UNVERIFIED -> "Not checked yet"
    VerifyState.VERIFIED -> "Confirmed"
    VerifyState.MISSING_REMOTE -> "Not found in Telegram"
    VerifyState.SIZE_MISMATCH -> "Different size in Telegram"
    VerifyState.UNCHECKABLE -> "Could not be checked"
}

/** Progress while a sweep runs. Total is unknown until the queue has been read, hence the branch. */
@Composable
private fun VerifyProgressCard(done: Int, total: Int) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Checking with Telegram…", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                if (total > 0) "${Format.count(done)}/${Format.count(total)} files"
                else "Working out which files are due…",
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
 * What the sweep found. "Repaired" is worth its own line because it is the part the user does not
 * have to act on: those files are already back in the upload queue.
 */
@Composable
private fun VerifyResultCard(result: VerifyResult, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when {
                    result.stoppedReason != null -> "Check stopped early"
                    result.nothingToDo -> "Nothing due"
                    result.problems > 0 -> "Found ${Format.count(result.problems)} problem(s)"
                    else -> "All good"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))

            if (result.nothingToDo) {
                Text(
                    "Every uploaded file has been checked. Turn the switch off to re-check the " +
                        "oldest ones.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "${Format.count(result.checked)} checked • " +
                        "${Format.count(result.confirmed)} confirmed",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (result.requeued > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "${Format.count(result.requeued)} file(s) are queued to upload again — run a " +
                        "backup and they are fixed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (result.problems > result.requeued) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "${Format.count(result.problems - result.requeued)} could not be fixed " +
                        "automatically — they are listed below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (result.unreachable > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "${Format.count(result.unreachable)} file(s) could not be checked this time. " +
                        "Nothing was changed for them; the next check tries them first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            result.stoppedReason?.let { reason ->
                Spacer(Modifier.height(10.dp))
                Text(
                    reason,
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
 * One file the sweep could not put right by itself. Which action is offered depends entirely on
 * whether the phone still has the bytes: with a local copy there is something to send, so the row
 * offers to queue it. Without one, the Telegram copy was the only copy and it is not there either,
 * so the only honest option left is to stop listing a file that no longer exists anywhere.
 */
@Composable
private fun ProblemRow(
    record: FileRecord,
    busy: Boolean,
    onRepair: () -> Unit,
    onForget: () -> Unit
) {
    val repairable = record.localState == LocalState.PRESENT

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
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
            verifyStateLabel(record.verifyState),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            localStateNote(record.localState),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (repairable) {
                TextButton(onClick = onRepair, enabled = !busy) { Text("Upload again") }
            }
            TextButton(onClick = onForget, enabled = !busy) { Text("Remove from list") }
        }
    }
}

/**
 * Why a file can or cannot be sent again, in the words of whatever happened to the local copy.
 * Removing the row is not a deletion: it only stops AirDrive claiming to hold something it does not.
 */
private fun localStateNote(state: LocalState): String = when (state) {
    LocalState.PRESENT -> "The phone still has this file, so it can be sent again."
    LocalState.MISSING -> "The phone's copy is gone too, so there is nothing left to upload."
    LocalState.FREED ->
        "AirDrive freed the phone's copy of this one, so there is nothing left to upload."
    LocalState.UNKNOWN ->
        "This entry came from Telegram rather than a scan, so there is no local file to send."
}
