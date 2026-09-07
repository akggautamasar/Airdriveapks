package com.airdrive.backup.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.ui.nav.Routes
import com.airdrive.backup.util.StorageAccess
import kotlinx.coroutines.launch

private val StorageBlue = Color(0xFF2F6FEA)
private val StorageBlueLight = Color(0xFFEAF2FF)
private val StorageGreen = Color(0xFF18A66A)
private val StorageGreenLight = Color(0xFFE6F8EF)
private val StorageCyan = Color(0xFF18B8C8)
private val StorageCyanLight = Color(0xFFE4F8FA)
private val StoragePurple = Color(0xFF7C3AED)
private val StoragePurpleLight = Color(0xFFF0E8FF)
private val StorageRed = Color(0xFFE5484D)
private val StorageRedLight = Color(0xFFFFE9E9)

@Composable
fun OnResumeEffect(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onResume)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) callback() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
fun StorageAccessScreen(nav: NavHostController, onboarding: Boolean = false) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    var hasAccess by remember { mutableStateOf(StorageAccess.hasFullAccess(context)) }
    OnResumeEffect { hasAccess = StorageAccess.hasFullAccess(context) }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasAccess = StorageAccess.hasFullAccess(context)
    }
    val wholeDevice by settings.scanWholeDevice.collectAsState(initial = true)
    val includeSdCard by settings.includeSdCard.collectAsState(initial = true)
    val enabledCategories by settings.enabledCategories.collectAsState(initial = BackupCategory.values().toSet())

    Scaffold(
        containerColor = Color(0xFFF7F9FD),
        topBar = {
            TopAppBar(
                title = { Text(if (onboarding) "Storage Access" else "Storage Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF7F9FD))
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (onboarding) {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = StorageBlueLight)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Give AirDrive access to your files", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "AirDrive needs storage access to find photos, videos, documents and other files for automatic backup. You can change these choices later in Settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            StorageCapacityCard(hasAccess, includeSdCard, context)
            SectionTitle("Scan locations")
            SettingCard {
                StorageSettingRow(Icons.Default.PhoneAndroid, "Scan internal storage", "Find files on your phone", StorageCyan, StorageCyanLight) {
                    Switch(checked = wholeDevice, onCheckedChange = { scope.launch { settings.setScanWholeDevice(it) } })
                }
                Divider()
                StorageSettingRow(Icons.Default.Usb, "Scan SD card / USB", "Include removable storage", StorageGreen, StorageGreenLight) {
                    Switch(checked = includeSdCard, enabled = wholeDevice, onCheckedChange = { scope.launch { settings.setIncludeSdCard(it) } })
                }
            }

            SectionTitle("Included folders")
            ActionCard(Icons.Default.FolderOpen, "Included folders", if (wholeDevice) "All folders on internal storage" else "Only selected folders", StorageGreen, StorageGreenLight) {
                nav.navigate(Routes.FOLDER_SELECT)
            }
            ActionCard(Icons.Default.Block, "Excluded folders", "Keep private folders out of backup", StorageRed, StorageRedLight) {
                nav.navigate(Routes.FOLDER_SELECT)
            }

            SectionTitle("File types")
            SettingCard {
                BackupCategory.values().forEachIndexed { index, category ->
                    val accent = when (category) {
                        BackupCategory.PHOTOS -> StorageBlue
                        BackupCategory.VIDEOS -> Color(0xFFF59E0B)
                        BackupCategory.PDFS -> StorageRed
                        BackupCategory.WORD_EXCEL -> StorageCyan
                        BackupCategory.AUDIO -> StoragePurple
                        BackupCategory.CALL_RECORDINGS -> StorageGreen
                        BackupCategory.OTHER_FILES -> Color(0xFF64748B)
                    }
                    val bg = when (category) {
                        BackupCategory.PHOTOS -> StorageBlueLight
                        BackupCategory.VIDEOS -> Color(0xFFFFF4DD)
                        BackupCategory.PDFS -> StorageRedLight
                        BackupCategory.WORD_EXCEL -> StorageCyanLight
                        BackupCategory.AUDIO -> StoragePurpleLight
                        BackupCategory.CALL_RECORDINGS -> StorageGreenLight
                        BackupCategory.OTHER_FILES -> Color(0xFFF1F5F9)
                    }
                    StorageSettingRow(
                        Icons.Default.Folder,
                        categoryLabel(category),
                        if (category in enabledCategories) "Included in backup" else "Not included",
                        accent,
                        bg
                    ) {
                        Checkbox(
                            checked = category in enabledCategories,
                            onCheckedChange = { checked ->
                                val next = if (checked) enabledCategories + category else enabledCategories - category
                                scope.launch { settings.setEnabledCategories(next) }
                            }
                        )
                    }
                    if (index < BackupCategory.values().lastIndex) Divider()
                }
            }

            SectionTitle("Storage tools")
            ActionCard(Icons.Default.CreateNewFolder, "Cleanup assistant", "Find duplicates and unnecessary files", StoragePurple, StoragePurpleLight) {
                nav.navigate(Routes.CLEANUP)
            }
            ActionCard(Icons.Default.Cloud, "Free up space", "Find backed-up files that can be removed", StorageBlue, StorageBlueLight) {
                nav.navigate(Routes.CLEANUP)
            }

            if (!hasAccess) {
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = StorageRedLight)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("All files access is required", fontWeight = FontWeight.Bold, color = StorageRed)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Allow AirDrive to scan your storage so automatic backup can find your files.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                if (StorageAccess.grantedFromSettingsScreen) StorageAccess.openAllFilesAccess(context)
                                else legacyPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StorageBlue)
                        ) { Text("Open permission settings") }
                    }
                }
            }

            if (onboarding) {
                Button(
                    onClick = {
                        nav.navigate(Routes.READY) {
                            popUpTo(Routes.STORAGE_ACCESS_ONBOARDING) { inclusive = true }
                        }
                    },
                    enabled = hasAccess,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StorageBlue)
                ) {
                    Text("Continue", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null)
                }
                Text(
                    if (hasAccess) "Storage access is ready. Continue to scan your files."
                    else "Grant storage access above to continue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun StorageCapacityCard(hasAccess: Boolean, includeSd: Boolean, context: android.content.Context) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = StorageBlueLight)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFFD5E7FF)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Storage, null, tint = StorageBlue, modifier = Modifier.size(27.dp))
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("Internal Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (hasAccess) StorageAccess.describeRoots(context, includeSd) else "Permission not granted",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                Spacer(Modifier.height(9.dp))
                LinearProgressIndicator(
                    progress = { 0.38f },
                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(5.dp)),
                    color = StorageBlue,
                    trackColor = Color(0xFFD2E1F7)
                )
            }
        }
    }
}

@Composable private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 2.dp))
}

@Composable private fun SettingCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(19.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(horizontal = 14.dp), content = content)
    }
}

@Composable private fun StorageSettingRow(icon: ImageVector, title: String, subtitle: String, accent: Color, background: Color, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(background), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        trailing()
    }
}

@Composable private fun ActionCard(icon: ImageVector, title: String, subtitle: String, accent: Color, background: Color, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(background), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
