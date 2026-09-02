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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.ui.screens.*

object Routes {
    const val WELCOME = "welcome"
    const val TELEGRAM_LOGIN = "telegram_login"
    const val STORAGE_ACCESS = "storage_access"
    const val FOLDER_SELECT = "folder_select"
    const val READY = "ready"
    const val DASHBOARD = "dashboard"
    const val BACKUP_PROGRESS = "backup_progress"
    const val ACTIVITY_HISTORY = "activity_history"
    const val CATEGORIES_STATS = "categories_stats"
    const val CHANNEL_CONFIG = "channel_config"
    const val BACKUP_SETTINGS = "backup_settings"
    const val FAILED_UPLOADS = "failed_uploads"
    const val ABOUT = "about"
}

@Composable
fun AppNav() {
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

    NavHost(navController = navController, startDestination = resolved) {
        composable(Routes.WELCOME) { WelcomeScreen(navController) }
        composable(Routes.TELEGRAM_LOGIN) { TelegramLoginScreen(navController) }
        composable(Routes.STORAGE_ACCESS) { StorageAccessScreen(navController) }
        composable(Routes.FOLDER_SELECT) { FolderSelectionScreen(navController) }
        composable(Routes.READY) { ReadyScreen(navController) }
        composable(Routes.DASHBOARD) { DashboardScreen(navController) }
        composable(Routes.BACKUP_PROGRESS) { BackupProgressScreen(navController) }
        composable(Routes.ACTIVITY_HISTORY) { ActivityHistoryScreen(navController) }
        composable(Routes.CATEGORIES_STATS) { CategoriesStatsScreen(navController) }
        composable(Routes.CHANNEL_CONFIG) { ChannelConfigScreen(navController) }
        composable(Routes.BACKUP_SETTINGS) { BackupSettingsScreen(navController) }
        composable(Routes.FAILED_UPLOADS) { FailedUploadsScreen(navController) }
        composable(Routes.ABOUT) { AboutScreen(navController) }
    }
}
