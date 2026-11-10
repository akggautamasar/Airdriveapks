package com.airdrive.backup.drive

import android.content.Context
import com.airdrive.backup.data.prefs.AppLockStore

/** Small isolated preferences for the cloud drive; does not touch the existing settings schema. */
object CloudDrivePrefs {
    private const val FILE = "airdrive_cloud_drive"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FREE_CACHE = "free_cache_on_close"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** User switch: show AirDrive as a storage location in the Files app and file pickers. */
    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, true)
    fun setEnabled(c: Context, value: Boolean) { prefs(c).edit().putBoolean(KEY_ENABLED, value).apply() }

    /** Delete Telegram's cached copy of a streamed file shortly after the last app closes it. */
    fun freeCacheOnClose(c: Context): Boolean = prefs(c).getBoolean(KEY_FREE_CACHE, true)
    fun setFreeCacheOnClose(c: Context, value: Boolean) { prefs(c).edit().putBoolean(KEY_FREE_CACHE, value).apply() }

    /**
     * The drive is reachable from other apps only when it is switched on AND App Lock is off.
     * Otherwise the Files app would be a way around the lock.
     */
    fun isOpen(c: Context): Boolean = isEnabled(c) && !AppLockStore(c).isEnabled()
}
