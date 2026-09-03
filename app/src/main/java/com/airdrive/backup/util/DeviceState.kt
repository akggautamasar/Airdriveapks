package com.airdrive.backup.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

/**
 * A read-only look at the two things that stop a backup for reasons that are nobody's fault: the
 * cable and the connection.
 *
 * WorkManager already enforces both as constraints, but a constraint enforces *silently* — it pulls
 * the run the moment the cable comes out and says nothing to anyone. That is how "AirDrive never
 * finishes a backup" gets reported: it did stop, deliberately, for a reason the phone knew and the
 * person did not. Everything here exists so the worker can name that reason in a notification.
 *
 * Every lookup is wrapped, because these system services are exactly the ones OEM builds like to
 * throw from, and a diagnosis that crashes is worse than no diagnosis at all.
 */
object DeviceState {

    /** True while plugged in on any kind of charger. */
    fun isCharging(context: Context): Boolean = runCatching {
        context.getSystemService(BatteryManager::class.java)?.isCharging == true
    }.getOrDefault(false)

    /** Battery level 0..100, or -1 when the platform will not say. */
    fun batteryPercent(context: Context): Int = runCatching {
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }.getOrDefault(-1)

    /**
     * Matches what WorkManager means by "battery not low": roughly the level at which the system
     * fires its own low-battery warning, and only while nothing is charging it back up.
     */
    fun isBatteryLow(context: Context): Boolean {
        if (isCharging(context)) return false
        val percent = batteryPercent(context)
        return percent in 0..LOW_BATTERY_PERCENT
    }

    /** Whether there is any usable network at all. */
    fun hasNetwork(context: Context): Boolean =
        capabilities(context)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    /** Whether the active network is one the user is not paying by the megabyte for. */
    fun isUnmetered(context: Context): Boolean =
        capabilities(context)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    private fun capabilities(context: Context): NetworkCapabilities? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        manager.getNetworkCapabilities(network)
    }.getOrNull()

    private const val LOW_BATTERY_PERCENT = 15
}
