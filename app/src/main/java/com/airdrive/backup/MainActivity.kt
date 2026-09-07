package com.airdrive.backup

import android.Manifest
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.ui.nav.AppNav
import com.airdrive.backup.ui.theme.AirDriveTheme
import com.airdrive.backup.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestedRoute = intent?.getStringExtra(EXTRA_ROUTE)

        setContent {
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            val settings = remember { SettingsStore(this) }
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            SideEffect {
                val bar = if (dark) AndroidColor.rgb(14, 20, 32) else AndroidColor.rgb(248, 249, 252)
                window.statusBarColor = bar
                window.navigationBarColor = bar
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }

            AirDriveTheme(mode = themeMode) {
                AppNav(deepLinkRoute = requestedRoute)
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "airdrive_route"
    }
}
