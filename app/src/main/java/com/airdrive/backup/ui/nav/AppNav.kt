package com.airdrive.backup.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.ui.screens.*

object Routes {
    const val WELCOME = "welcome"
    const val TELEGRAM_LOGIN = "telegram_login"
    const val API_CREDENTIALS = "api_credentials"
    const val STORAGE_ACCESS = "storage_access"
    const val FOLDER_SELECT = "folder_select"
    const val READY = "ready"
    const val DASHBOARD = "dashboard"
    const val BACKUP_PROGRESS = "backup_progress"
    const val ACTIVITY_HISTORY = "activity_history"
    const val CATEGORIES_STATS = "categories_stats"
    const val DESTINATION = "destination"
    const val CHANNEL_CONFIG = "channel_config"
    const val BACKUP_SETTINGS = "backup_settings"
    const val ADVANCED_SETTINGS = "advanced_settings"
    const val RESTORE = "restore"
    const val FAILED_UPLOADS = "failed_uploads"
    const val ABOUT = "about"

    /** Backup history, and one run's worth of detail (append "/{runId}"). */
    const val TIMELINE = "timeline"
    const val RUN_DETAIL = "run_detail"

    /** Files that vanished from the phone but are still safe in Telegram. */
    const val DELETED_FILES = "deleted_files"

    const val SEARCH = "search"
    const val GALLERY = "gallery"
    const val MIGRATE = "migrate"
    const val CLEANUP = "cleanup"

    /**
     * Backup profiles, deferred. Deliberately has no composable and nothing navigating to it: the
     * constant is a placeholder, and adding a menu entry for it before the screen exists would
     * navigate to a destination NavHost does not know about, which throws.
     */
    const val PROFILES = "profiles"

    const val VERIFY = "verify"

    /** Older copies of a file that are still sitting in Telegram. */
    const val FILE_HISTORY = "file_history"
}

@Composable
fun AppNav(deepLinkRoute: String? = null) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val navController = rememberNavController()

    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.flow.combine(
            settings.onboardingDone,
            settings.telegramLoggedIn
        ) { done, loggedIn -> done to loggedIn }.collect { (done, loggedIn) ->
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
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    // A notification tap asks for one specific screen. Only honoured once the app is past
    // onboarding — sending someone who has not signed in yet to the timeline would be useless.
    LaunchedEffect(resolved, deepLinkRoute) {
        if (deepLinkRoute != null && deepLinkRoute != resolved && resolved == Routes.DASHBOARD) {
            runCatching { navController.navigate(deepLinkRoute) }
        }
    }

    NavHost(navController = navController, startDestination = resolved) {
        composable(Routes.WELCOME) { WelcomeScreen(navController) }
        composable(Routes.TELEGRAM_LOGIN) { TelegramLoginScreen(navController) }
        composable(Routes.API_CREDENTIALS) { ApiCredentialsScreen(navController) }
        composable(Routes.STORAGE_ACCESS) { StorageAccessScreen(navController) }
        composable(Routes.FOLDER_SELECT) { FolderSelectionScreen(navController) }
        composable(Routes.READY) { ReadyScreen(navController) }
        composable(Routes.DASHBOARD) { DashboardScreen(navController) }
        composable(Routes.BACKUP_PROGRESS) { BackupProgressScreen(navController) }
        composable(Routes.ACTIVITY_HISTORY) { ActivityHistoryScreen(navController) }
        composable(Routes.CATEGORIES_STATS) { CategoriesStatsScreen(navController) }
        composable(Routes.DESTINATION) { DestinationScreen(navController) }
        composable(Routes.CHANNEL_CONFIG) { ChannelConfigScreen(navController) }
        composable(Routes.BACKUP_SETTINGS) { BackupSettingsScreen(navController) }
        composable(Routes.ADVANCED_SETTINGS) { AdvancedSettingsScreen(navController) }
        composable(Routes.RESTORE) { RestoreScreen(navController) }
        composable(Routes.FAILED_UPLOADS) { FailedUploadsScreen(navController) }
        composable(Routes.ABOUT) { AboutScreen(navController) }
        composable(Routes.TIMELINE) { BackupTimelineScreen(navController) }
        composable(Routes.DELETED_FILES) { DeletedFilesScreen(navController) }
        composable(Routes.SEARCH) { SearchScreen(navController) }
        composable(Routes.GALLERY) { GalleryScreen(navController) }
        composable(Routes.MIGRATE) { MigrationScreen(navController) }
        composable(Routes.CLEANUP) { CleanupScreen(navController) }
        composable(Routes.VERIFY) { VerifyScreen(navController) }
        composable(Routes.FILE_HISTORY) { FileHistoryScreen(navController) }
        composable(
            route = "${Routes.RUN_DETAIL}/{runId}",
            arguments = listOf(navArgument("runId") { type = NavType.LongType })
        ) { entry ->
            RunDetailScreen(navController, entry.arguments?.getLong("runId") ?: 0L)
        }
    }
}
