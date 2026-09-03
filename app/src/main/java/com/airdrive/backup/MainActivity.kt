package com.airdrive.backup

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.ui.nav.AppNav
import com.airdrive.backup.ui.theme.AirDriveTheme
import com.airdrive.backup.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            val settings = remember { SettingsStore(this) }
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)

            AirDriveTheme(mode = themeMode) {
                AppNav()
            }
        }
    }
}
