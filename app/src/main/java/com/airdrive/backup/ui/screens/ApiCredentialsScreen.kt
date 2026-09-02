package com.airdrive.backup.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.telegram.AuthState
import com.airdrive.backup.telegram.TdClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Lets any user run their own copy of AirDrive with their own Telegram application. Telegram ties
 * api_id/api_hash to a person, so a published APK cannot ship one that everybody shares — each
 * user creates an application at my.telegram.org and pastes the pair in here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiCredentialsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val tdClient = remember { TdClient.get(context) }
    val scope = rememberCoroutineScope()

    val authState by tdClient.authState.collectAsState()

    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var fromUser by remember { mutableStateOf(false) }
    var buildHasKeys by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    // Pre-fill with whatever is stored so the screen doubles as "review / change my keys".
    LaunchedEffect(Unit) {
        val creds = settings.apiCredentials.first()
        fromUser = creds.fromUser
        buildHasKeys = !creds.fromUser && creds.isUsable
        if (creds.fromUser) {
            apiId = creds.apiId.toString()
            apiHash = creds.apiHash
        }
        loaded = true
    }

    fun save() {
        val id = apiId.trim().toIntOrNull()
        val hash = apiHash.trim()
        if (id == null || id <= 0) {
            status = "api_id must be the number Telegram showed you"
            isError = true
            return
        }
        if (hash.length < 16) {
            status = "That api_hash looks too short — it is a 32-character string"
            isError = true
            return
        }
        scope.launch {
            settings.setApiCredentials(id, hash)
            fromUser = true
            isError = false
            status = "Saved. Connecting to Telegram…"
            // TDLib is still parked in WaitTdlibParameters, so it only needs poking.
            tdClient.retryTdlibParameters()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Telegram API keys") },
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
            Text(
                "AirDrive talks to Telegram as its own app, and Telegram issues those keys per " +
                    "person. Creating a pair takes about a minute and is free.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            Text("How to get them", style = MaterialTheme.typography.titleMedium)
            Text(
                "1. Open my.telegram.org and log in with your phone number.\n" +
                    "2. Choose “API development tools”.\n" +
                    "3. Fill in any app name and short name, platform Android.\n" +
                    "4. Copy the api_id (a number) and api_hash (a long string) it shows.",
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://my.telegram.org/apps"))
                        )
                    }
                }
            ) { Text("Open my.telegram.org") }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiId,
                onValueChange = { apiId = it.filter { c -> c.isDigit() }.take(12) },
                label = { Text("api_id") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = apiHash,
                onValueChange = { apiHash = it.trim() },
                label = { Text("api_hash") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { save() },
                enabled = loaded && apiId.isNotBlank() && apiHash.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save and connect") }

            if (fromUser && buildHasKeys) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            settings.clearApiCredentials()
                            apiId = ""
                            apiHash = ""
                            fromUser = false
                            isError = false
                            status = "Back to the keys this build was compiled with."
                            tdClient.retryTdlibParameters()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Use this build's own keys") }
            }

            status?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    authState == AuthState.NEEDS_CREDENTIALS && fromUser ->
                        "Telegram has not accepted these keys yet."
                    authState == AuthState.NEEDS_CREDENTIALS ->
                        "Waiting for keys before AirDrive can sign in."
                    authState == AuthState.READY -> "Signed in to Telegram."
                    else -> "Telegram connection: ${authState.name.lowercase().replace('_', ' ')}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(4.dp))
            Text(
                "Your keys stay on this phone. They are stored in the app's private settings and " +
                    "are never sent anywhere except Telegram.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
