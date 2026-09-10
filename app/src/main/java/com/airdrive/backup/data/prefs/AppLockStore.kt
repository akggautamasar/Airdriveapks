package com.airdrive.backup.data.prefs

import android.content.Context

/** Small isolated preference so app-lock state does not affect the existing settings schema. */
class AppLockStore(context: Context) {
    private val prefs = context.getSharedPreferences("airdrive_app_lock", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
    }
}
