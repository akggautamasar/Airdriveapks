package com.airdrive.backup

import android.app.Application
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.telegram.TdClient
import com.airdrive.backup.util.NotificationHelper
import com.airdrive.backup.work.WorkScheduler
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
        Client.execute(org.drinkless.tdlib.TdApi.SetLogVerbosityLevel(1))
        appScope.launch { SettingsStore(this@AirDriveApp).pinInstallGeneration() }
        TdClient.get(this)
        // Reconcile connected Telegram channels periodically even when no screen is open.
        WorkScheduler.rescheduleTelegramAutoSync(this)
    }
}
