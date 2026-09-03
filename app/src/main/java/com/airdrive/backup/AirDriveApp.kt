package com.airdrive.backup

import android.app.Application
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.telegram.TdClient
import com.airdrive.backup.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.drinkless.tdlib.Client

class AirDriveApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
        // Route TDLib's own internal logs into Android's Logcat at a conservative
        // verbosity, and never log authentication/session payloads (see docs).
        Client.execute(org.drinkless.tdlib.TdApi.SetLogVerbosityLevel(1))
        // Decides once, before any screen can change a setting, whether this install predates the
        // per-category-channel defaults. Without it an upgrading user would silently inherit the
        // hard-coded legacy channel ids the first time they opened the destination screen.
        appScope.launch { SettingsStore(this@AirDriveApp).pinInstallGeneration() }
        TdClient.get(this)
    }
}
