package com.airdrive.backup.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.airdrive.backup.data.backup.ManifestSync
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.telegram.AuthState
import com.airdrive.backup.telegram.TdClient
import com.airdrive.backup.ui.nav.Routes
import kotlinx.coroutines.delay
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
    var restoreStatus by remember { mutableStateOf<String?>(null) }
    val blue = Color(0xFF2F6FEA)
    val blueSoft = Color(0xFFEAF2FF)

    LaunchedEffect(authState) {
        if (authState == AuthState.READY) {
            settings.setTelegramLoggedIn(true)
            restoreStatus = "Checking Telegram for previous backup data…"
            val result = BackupRepository.get(context).restoreManifestIfFreshInstall()
            restoreStatus = when (result) {
                is ManifestSync.RestoreResult.Restored -> "Found ${result.fileCount} previously backed-up file(s)."
                ManifestSync.RestoreResult.NoManifestFound, ManifestSync.RestoreResult.NothingToDo -> null
                ManifestSync.RestoreResult.NotSignedIn -> null
                is ManifestSync.RestoreResult.Failed -> null
            }
            if (restoreStatus != null) delay(1200)
            val target = if (settings.onboardingDone.first()) Routes.DASHBOARD else Routes.STORAGE_ACCESS_ONBOARDING
            nav.navigate(target) { popUpTo(Routes.WELCOME) { inclusive = true } }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F9FD)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(22.dp))
            Surface(modifier = Modifier.size(64.dp), shape = CircleShape, color = blueSoft) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Send, contentDescription = null, tint = blue, modifier = Modifier.size(34.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Connect Telegram", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(7.dp))
            Text("Use your Telegram account as your personal backup destination.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)

            Spacer(Modifier.height(28.dp))
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Color.White) {
                Column(Modifier.padding(18.dp)) {
                    when (authState) {
                        AuthState.NEEDS_CREDENTIALS -> {
                            Text("Telegram API access", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("Add your own Telegram API keys to securely sign in.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { nav.navigate(Routes.API_CREDENTIALS) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Add API keys") }
                        }
                        AuthState.UNKNOWN -> {
                            Text("Preparing secure connection…", fontWeight = FontWeight.SemiBold)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), color = blue)
                        }
                        AuthState.WAIT_PHONE_NUMBER -> {
                            Text("Your phone number", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("Include the country code, for example +91.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(14.dp))
                            OutlinedTextField(value = phone, onValueChange = { phone = it }, singleLine = true, label = { Text("Phone number") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = {
                                submitting = true; errorText = null
                                scope.launch {
                                    try { tdClient.submitPhoneNumber(phone.trim()) } catch (e: Exception) { errorText = e.message } finally { submitting = false }
                                }
                            }, enabled = phone.isNotBlank() && !submitting, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text(if (submitting) "Sending…" else "Send code") }
                        }
                        AuthState.WAIT_CODE -> {
                            Text("Verify your account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("Enter the login code Telegram sent you.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(14.dp))
                            OutlinedTextField(value = code, onValueChange = { code = it }, singleLine = true, label = { Text("Login code") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = {
                                submitting = true; errorText = null
                                scope.launch {
                                    try { tdClient.submitCode(code.trim()) } catch (e: Exception) { errorText = e.message } finally { submitting = false }
                                }
                            }, enabled = code.isNotBlank() && !submitting, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text(if (submitting) "Verifying…" else "Verify") }
                        }
                        AuthState.WAIT_PASSWORD -> {
                            Text("Two-step verification", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("Enter your Telegram password to finish signing in.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(14.dp))
                            OutlinedTextField(value = password, onValueChange = { password = it }, singleLine = true, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = {
                                submitting = true; errorText = null
                                scope.launch {
                                    try { tdClient.submitPassword(password) } catch (e: Exception) { errorText = e.message } finally { submitting = false }
                                }
                            }, enabled = password.isNotBlank() && !submitting, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text(if (submitting) "Verifying…" else "Unlock") }
                        }
                        AuthState.READY, AuthState.LOGGED_OUT, AuthState.CLOSED -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 3.dp, color = blue)
                                Spacer(Modifier.width(12.dp))
                                Text(restoreStatus ?: "Connected. Continuing…", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = blueSoft) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = blue, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Your Telegram account remains yours. AirDrive uses it as the backup destination.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            lastAuthError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp)) }
            errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp)) }
        }
    }
}
