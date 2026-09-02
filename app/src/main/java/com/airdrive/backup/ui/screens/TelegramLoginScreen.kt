package com.airdrive.backup.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.telegram.AuthState
import com.airdrive.backup.telegram.TdClient
import com.airdrive.backup.ui.nav.Routes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun TelegramLoginScreen(nav: NavHostController) {
    val context = LocalContext.current
    val tdClient = remember { TdClient.get(context) }
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    val authState by tdClient.authState.collectAsState()
    val lastAuthError by tdClient.lastAuthError.collectAsState()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    LaunchedEffect(authState) {
        if (authState == AuthState.READY) {
            settings.setTelegramLoggedIn(true)
            // Fire-and-forget: checks Saved Messages for a manifest from a previous install and,
            // if the local DB is otherwise empty, restores it (already-backed-up files + old
            // destination settings). Not awaited — login should not stall on a network round
            // trip, and it is a no-op the vast majority of the time (a normal, non-fresh install).
            scope.launch { BackupRepository.get(context).restoreManifestIfFreshInstall() }
            // Onboarding no longer routes through a mandatory folder picker; it asks for storage
            // access instead, and only the first time.
            val target = if (settings.onboardingDone.first()) Routes.DASHBOARD else Routes.STORAGE_ACCESS
            nav.navigate(target) {
                popUpTo(Routes.WELCOME) { inclusive = true }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Connect Telegram", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))

            when (authState) {
                // TDLib is up but has no usable api_id/api_hash: the only way forward is for the
                // user to bring their own from my.telegram.org.
                AuthState.NEEDS_CREDENTIALS -> {
                    Text(
                        "AirDrive needs your own Telegram API keys before it can sign in.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    lastAuthError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { nav.navigate(Routes.API_CREDENTIALS) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Add Telegram API keys") }
                }
                AuthState.UNKNOWN -> {
                    Text("Preparing secure connection\u2026", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                }
                AuthState.WAIT_PHONE_NUMBER -> {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone number (with country code)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            submitting = true
                            errorText = null
                            scope.launch {
                                try {
                                    tdClient.submitPhoneNumber(phone.trim())
                                } catch (e: Exception) {
                                    errorText = e.message
                                } finally {
                                    submitting = false
                                }
                            }
                        },
                        enabled = phone.isNotBlank() && !submitting,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (submitting) "Sending\u2026" else "Send code") }
                }
                AuthState.WAIT_CODE -> {
                    Text("Enter the login code Telegram sent you", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Login code") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            submitting = true
                            errorText = null
                            scope.launch {
                                try {
                                    tdClient.submitCode(code.trim())
                                } catch (e: Exception) {
                                    errorText = e.message
                                } finally {
                                    submitting = false
                                }
                            }
                        },
                        enabled = code.isNotBlank() && !submitting,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (submitting) "Verifying\u2026" else "Verify") }
                }
                AuthState.WAIT_PASSWORD -> {
                    Text("Two-step verification is on \u2014 enter your password", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            submitting = true
                            errorText = null
                            scope.launch {
                                try {
                                    tdClient.submitPassword(password)
                                } catch (e: Exception) {
                                    errorText = e.message
                                } finally {
                                    submitting = false
                                }
                            }
                        },
                        enabled = password.isNotBlank() && !submitting,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (submitting) "Verifying\u2026" else "Unlock") }
                }
                AuthState.READY, AuthState.LOGGED_OUT, AuthState.CLOSED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Connected. Continuing\u2026")
                    }
                }
            }

            errorText?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
