package com.airdrive.backup.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.ui.screens.*
import com.airdrive.backup.ui.theme.AirNavSelected

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
        kotlinx.coroutines.flow.combine(settings.onboardingDone, settings.telegramLoggedIn) { done, loggedIn -> done to loggedIn }.collect { (done, loggedIn) ->
            if (startDestination == null) startDestination = when {
                !done -> Routes.WELCOME
                !loggedIn -> Routes.TELEGRAM_LOGIN
                else -> Routes.DASHBOARD
            }
        }
    }
    val resolved = startDestination
    if (resolved == null) {
        Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        return
    }
    LaunchedEffect(resolved, deepLinkRoute) {
        if (deepLinkRoute != null && deepLinkRoute != resolved && resolved == Routes.DASHBOARD) {
            runCatching { navController.navigate(deepLinkRoute) }
        }
    }

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val showBottomBar = bottomTabs.any { it.route == currentRoute }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                bottomTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            if (currentRoute != tab.route) navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, tab.label) },
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
    ) { scaffoldPadding ->
        NavHost(navController, startDestination = resolved, modifier = Modifier.padding(scaffoldPadding)) {
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
            composable(Routes.CATEGORIES_STATS) { CategoriesStatsScreen(navController) }
            composable(Routes.DESTINATION) { DestinationScreen(navController) }
            composable(Routes.CHANNEL_CONFIG) { ChannelConfigScreen(navController) }
            composable(Routes.TELEGRAM_SETTINGS) { TelegramSettingsScreen(navController) }
            composable(Routes.BACKUP_SETTINGS) { BackupSettingsScreen(navController) }
            composable(Routes.ADVANCED_SETTINGS) { AdvancedSettingsScreen(navController) }
            composable(Routes.SETTINGS) { SettingsOverviewScreen(navController) }
            composable(Routes.RESTORE) { RestoreAllScreen(navController) }
            composable(Routes.FAILED_UPLOADS) { FailedUploadsScreen(navController) }
            composable(Routes.ABOUT) { AboutScreen(navController) }
            composable(Routes.TIMELINE) { BackupTimelineScreen(navController) }
            composable(Routes.DELETED_FILES) { DeletedFilesScreen(navController) }
            composable(Routes.SEARCH) { SearchScreen(navController) }
            composable(Routes.GALLERY) { IncrementalGalleryScreen(navController) }
            composable("${Routes.CATEGORY_DETAIL}/{category}", arguments = listOf(navArgument("category") { type = NavType.StringType })) { entry ->
                val categoryName = entry.arguments?.getString("category")
                val category = BackupCategory.values().find { it.name == categoryName } ?: BackupCategory.OTHER_FILES
                ReferenceCategoryDetailScreen(navController, category)
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
