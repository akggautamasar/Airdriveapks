package com.airdrive.backup.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.ui.screens.*
import com.airdrive.backup.ui.theme.AirNavSelected
import kotlinx.coroutines.flow.combine

object Routes {
    const val WELCOME = "welcome"
    const val TELEGRAM_LOGIN = "telegram_login"
    const val API_CREDENTIALS = "api_credentials"
    const val STORAGE_ACCESS = "storage_access"
    const val STORAGE_ACCESS_ONBOARDING = "storage_access_onboarding"
    const val FOLDER_SELECT = "folder_select"
    const val READY = "ready"
    const val DASHBOARD = "dashboard"
    const val BACKUP_PROGRESS = "backup_progress"
    const val ACTIVITY_HISTORY = "activity_history"
    const val CATEGORIES_STATS = "categories_stats"
    const val DESTINATION = "destination"
    const val CHANNEL_CONFIG = "channel_config"
    const val TELEGRAM_SETTINGS = "telegram_settings"
    const val BACKUP_SETTINGS = "backup_settings"
    const val ADVANCED_SETTINGS = "advanced_settings"
    const val RESTORE = "restore"
    const val FAILED_UPLOADS = "failed_uploads"
    const val ABOUT = "about"
    const val TIMELINE = "timeline"
    const val RUN_DETAIL = "run_detail"
    const val DELETED_FILES = "deleted_files"
    const val SEARCH = "search"
    const val GALLERY = "gallery"
    const val CATEGORY_DETAIL = "category_detail"
    const val MIGRATE = "migrate"
    const val CLEANUP = "cleanup"
    const val PROFILES = "profiles"
    const val VERIFY = "verify"
    const val FILE_HISTORY = "file_history"
    const val SETTINGS = "settings"
    const val SECURITY_PRIVACY = "security_privacy"
    const val NOTIFICATIONS = "notifications"
    const val APPEARANCE = "appearance"
    const val NETWORK = "network"
    const val FILE_VIEWER = "file_viewer"
}

private data class BottomTab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val bottomTabs = listOf(
    BottomTab(Routes.DASHBOARD, "Home", Icons.Filled.Home),
    BottomTab(Routes.CATEGORIES_STATS, "Files", Icons.Filled.Folder),
    BottomTab(Routes.RESTORE, "Restore", Icons.Filled.CloudDownload),
    BottomTab(Routes.ACTIVITY_HISTORY, "Activity", Icons.Filled.History),
    BottomTab(Routes.SETTINGS, "Settings", Icons.Filled.Settings)
)

@Composable
fun AppNav(deepLinkRoute: String? = null) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val navController = rememberNavController()
    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        combine(settings.onboardingDone, settings.telegramLoggedIn) { done, loggedIn -> done to loggedIn }.collect { (done, loggedIn) ->
            if (startDestination == null) {
                startDestination = when {
                    !done -> Routes.WELCOME
                    !loggedIn -> Routes.TELEGRAM_LOGIN
                    else -> Routes.DASHBOARD
                }
            }
        }
    }

    val resolved = startDestination
    if (resolved == null) {
        LaunchScreen()
        return
    }

    LaunchedEffect(resolved, deepLinkRoute) {
        if (deepLinkRoute != null && deepLinkRoute != resolved && resolved == Routes.DASHBOARD) {
            runCatching { navController.navigate(deepLinkRoute) }
        }
    }

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val showBottomBar = currentRoute == Routes.BACKUP_PROGRESS || currentRoute == Routes.FILE_VIEWER || bottomTabs.any { it.route == currentRoute }
    val selectedTabRoute = if (currentRoute == Routes.BACKUP_PROGRESS || currentRoute == Routes.FILE_VIEWER) Routes.CATEGORIES_STATS else currentRoute

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                bottomTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTabRoute == tab.route,
                        onClick = {
                            if (currentRoute != tab.route) {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSurface,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = AirNavSelected,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(navController = navController, startDestination = resolved, modifier = Modifier.padding(paddingValues)) {
            composable(Routes.WELCOME) { WelcomeScreen(navController) }
            composable(Routes.TELEGRAM_LOGIN) { TelegramLoginScreen(navController) }
            composable(Routes.API_CREDENTIALS) { ApiCredentialsScreen(navController) }
            composable(Routes.STORAGE_ACCESS) { StorageAccessScreen(navController, onboarding = false) }
            composable(Routes.STORAGE_ACCESS_ONBOARDING) { StorageAccessScreen(navController, onboarding = true) }
            composable(Routes.FOLDER_SELECT) { FolderSelectionScreen(navController) }
            composable(Routes.READY) { ReadyScreen(navController) }
            composable(Routes.DASHBOARD) { DashboardScreen(navController) }
            composable(Routes.BACKUP_PROGRESS) { BackupProgressScreen(navController) }
            composable(Routes.ACTIVITY_HISTORY) { ActivityHistoryScreen(navController) }
            composable(Routes.CATEGORIES_STATS) { FilesHubScreen(navController) }
            composable(Routes.DESTINATION) { DestinationScreen(navController) }
            composable(Routes.CHANNEL_CONFIG) { ChannelConfigScreen(navController) }
            composable(Routes.TELEGRAM_SETTINGS) { TelegramSettingsScreen(navController) }
            composable(Routes.BACKUP_SETTINGS) { BackupSettingsScreen(navController) }
            composable(Routes.ADVANCED_SETTINGS) { AdvancedSettingsScreen(navController) }
            composable(Routes.SETTINGS) { SettingsOverviewScreen(navController) }
            composable(Routes.SECURITY_PRIVACY) { SecurityPrivacyScreen(navController) }
            composable(Routes.NOTIFICATIONS) { NotificationsSettingsScreen(navController) }
            composable(Routes.APPEARANCE) { AppearanceSettingsScreen(navController) }
            composable(Routes.NETWORK) { NetworkSettingsScreen(navController) }
            composable(Routes.RESTORE) { RestoreAllScreen(navController) }
            composable(Routes.FAILED_UPLOADS) { FailedUploadsScreen(navController) }
            composable(Routes.ABOUT) { AboutScreen(navController) }
            composable(Routes.TIMELINE) { BackupTimelineScreen(navController) }
            composable(Routes.DELETED_FILES) { DeletedFilesScreen(navController) }
            composable(Routes.SEARCH) { SearchScreen(navController) }
            composable(Routes.GALLERY) { IncrementalGalleryScreen(navController) }
            composable("${Routes.CATEGORY_DETAIL}/{category}", arguments = listOf(navArgument("category") { type = NavType.StringType })) { entry ->
                val raw = entry.arguments?.getString("category")
                val category = BackupCategory.values().find { it.name == raw }
                FilesHubScreen(navController, category)
            }
            composable("${Routes.FILE_VIEWER}/{recordId}", arguments = listOf(navArgument("recordId") { type = NavType.LongType })) { entry ->
                FileViewerScreen(navController, entry.arguments?.getLong("recordId") ?: 0L)
            }
            composable(Routes.MIGRATE) { MigrationScreen(navController) }
            composable(Routes.CLEANUP) { CleanupScreen(navController) }
            composable(Routes.VERIFY) { VerifyScreen(navController) }
            composable(Routes.FILE_HISTORY) { FileHistoryScreen(navController) }
            composable("${Routes.RUN_DETAIL}/{runId}", arguments = listOf(navArgument("runId") { type = NavType.LongType })) { entry ->
                RunDetailScreen(navController, entry.arguments?.getLong("runId") ?: 0L)
            }
        }
    }
}

@Composable
private fun LaunchScreen() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(modifier = Modifier.size(88.dp), shape = RoundedCornerShape(26.dp), color = Color(0xFF2F6FEA), shadowElevation = 12.dp) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Cloud, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                }
            }
            Spacer(Modifier.size(20.dp))
            Text("AirDrive", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(5.dp))
            Text("Your files. Backed up. Always with you.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(26.dp))
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp, color = Color(0xFF2F6FEA))
        }
    }
}
