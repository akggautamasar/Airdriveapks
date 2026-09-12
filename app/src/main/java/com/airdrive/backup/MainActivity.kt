package com.airdrive.backup

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.airdrive.backup.data.prefs.AppLockStore
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.ui.nav.AppNav
import com.airdrive.backup.ui.theme.AirDriveTheme
import com.airdrive.backup.ui.theme.ThemeMode

class MainActivity : FragmentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lockAuthenticating = false
    private var hasResumedOnce = false
    private var pausedAt = 0L

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!QuantxDrivePip.isEnabled) return
        if (isFinishing || isChangingConfigurations || isInPictureInPictureMode) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                val entered = enterPictureInPictureMode(params)
                if (entered) {
                    QuantxDrivePip.isInPip.value = true
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        QuantxDrivePip.isInPip.value = isInPictureInPictureMode
    }

    override fun onResume() {
        super.onResume()
        QuantxDrivePip.isInPip.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            isInPictureInPictureMode
        } else {
            false
        }
        val shouldPrompt = !hasResumedOnce || pausedAt == 0L ||
            SystemClock.elapsedRealtime() - pausedAt > 700L
        hasResumedOnce = true
        if (shouldPrompt) {
            mainHandler.postDelayed({ maybePromptForAppLock() }, 450L)
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacksAndMessages(null)
        pausedAt = SystemClock.elapsedRealtime()
        super.onPause()
    }

    private fun maybePromptForAppLock() {
        if (lockAuthenticating || isFinishing || isChangingConfigurations || isDestroyed) return
        if (!AppLockStore(this).isEnabled()) return

        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val availability = runCatching {
            BiometricManager.from(this).canAuthenticate(authenticators)
        }.getOrElse { return }
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) return

        lockAuthenticating = true
        runCatching {
            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        lockAuthenticating = false
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence
                    ) {
                        lockAuthenticating = false
                        if (!isFinishing && !isDestroyed) finishAndRemoveTask()
                    }

                    override fun onAuthenticationFailed() = Unit
                }
            )

            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock AirDrive")
                    .setSubtitle("Authenticate to access your backups and files")
                    .setAllowedAuthenticators(authenticators)
                    .build()
            )
        }.onFailure {
            lockAuthenticating = false
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestedRoute = intent?.getStringExtra(EXTRA_ROUTE)

        setContent {
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

/** Shared bridge between the QuantxDrive viewer and the hosting Activity. */
object QuantxDrivePip {
    @Volatile
    var isEnabled: Boolean = false

    val isInPip = androidx.compose.runtime.mutableStateOf(false)

    @Volatile
    var isFullscreen: Boolean = false
}
