package com.airdrive.backup

import android.app.Application
import com.airdrive.backup.telegram.TdClient
import com.airdrive.backup.util.NotificationHelper
import org.drinkless.tdlib.Client

class AirDriveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
        // Route TDLib's own internal logs into Android's Logcat at a conservative
        // verbosity, and never log authentication/session payloads (see docs).
        Client.execute(org.drinkless.tdlib.TdApi.SetLogVerbosityLevel(1))
        TdClient.get(this)
    }
}
