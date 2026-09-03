package com.airdrive.backup.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.airdrive.backup.MainActivity
import com.airdrive.backup.R

/**
 * Two channels on purpose. Progress is a silent, ongoing notification that would be maddening if
 * it buzzed; the result is a one-off the user is meant to notice ("7 files failed — tap to
 * review"), so it gets its own channel at default importance and can be muted separately.
 */
object NotificationHelper {
    const val CHANNEL_ID = "airdrive_backup"
    const val RESULT_CHANNEL_ID = "airdrive_results"
    const val BACKUP_NOTIFICATION_ID = 4201
    const val RESULT_NOTIFICATION_ID = 4202

    /**
     * A migration needs an id of its own. It is a second foreground service, and a backup can be
     * running at the same time — sharing [BACKUP_NOTIFICATION_ID] would have the two of them
     * overwriting each other's progress in one notification.
     */
    const val MIGRATION_NOTIFICATION_ID = 4203

    /**
     * "Waiting for charging" gets an id of its own so it can outlive the run that produced it. It is
     * not a result — the run is not over, it is parked — and posting it as one would have the next
     * finished backup silently overwrite the only explanation the user was ever given.
     */
    const val WAITING_NOTIFICATION_ID = 4204

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)

        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_backup),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notif_channel_backup_desc)
                    setShowBadge(false)
                }
            )
        }

        if (manager.getNotificationChannel(RESULT_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    RESULT_CHANNEL_ID,
                    context.getString(R.string.notif_channel_results),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notif_channel_results_desc)
                }
            )
        }
    }

    /**
     * Android 13+ silently drops notifications without the runtime permission. Checked rather than
     * assumed so a declined prompt does not look like a broken backup.
     */
    fun canPost(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /** Opens the app, optionally straight onto a screen (see MainActivity.EXTRA_ROUTE). */
    private fun openApp(context: Context, route: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (route != null) putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(
            context,
            // A distinct request code per route, otherwise FLAG_UPDATE_CURRENT hands back the
            // previous notification's extras and every tap lands on the same screen.
            route?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * The ongoing progress notification. [icon] and [route] exist for the migration, which is a
     * download rather than an upload and which should reopen its own screen when tapped.
     */
    fun buildProgressNotification(
        context: Context,
        title: String,
        text: String,
        progressPercent: Int,
        indeterminate: Boolean = false,
        icon: Int = android.R.drawable.stat_sys_upload,
        route: String? = null
    ): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context, route))
            .setProgress(100, progressPercent, indeterminate)
            .build()

    /**
     * The end-of-run notification. [lines] is the detail block — a run's real numbers rather than
     * "Backup finished", which told the user nothing they could act on.
     */
    fun notifyResult(
        context: Context,
        title: String,
        summary: String,
        lines: List<String> = emptyList(),
        route: String? = null,
        alert: Boolean = false,
        icon: Int = android.R.drawable.stat_sys_upload_done
    ) {
        ensureChannel(context)
        if (!canPost(context)) {
            Log.i("AirDrive.Notify", "notifications not permitted; skipping \"$title\"")
            return
        }
        val body = (listOf(summary) + lines).filter { it.isNotBlank() }.joinToString("\n")
        val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .setPriority(if (alert) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context, route))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(RESULT_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w("AirDrive.Notify", "notify refused: ${e.message}")
        }
    }

    /** Clears the result notification, e.g. when a new run starts. */
    fun clearResult(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(RESULT_NOTIFICATION_ID) }
    }

    /**
     * "Backup paused — waiting for charging". Deliberately quiet: the phone is doing the sensible
     * thing and there is nothing for the user to fix, so this is an explanation left where they will
     * find it rather than an interruption. Unlike [notifyResult] it does not clear itself on tap —
     * the condition is still true after tapping, and a notice that vanishes before the cable goes
     * back in is a notice that was never read.
     */
    fun notifyWaiting(
        context: Context,
        title: String,
        summary: String,
        lines: List<String> = emptyList(),
        route: String? = null
    ) {
        ensureChannel(context)
        if (!canPost(context)) {
            Log.i("AirDrive.Notify", "notifications not permitted; skipping \"$title\"")
            return
        }
        val body = (listOf(summary) + lines).filter { it.isNotBlank() }.joinToString("\n")
        val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(context, route))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(WAITING_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w("AirDrive.Notify", "notify refused: ${e.message}")
        }
    }

    /** Clears the paused notice — the run got going again, so the explanation is now wrong. */
    fun clearWaiting(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(WAITING_NOTIFICATION_ID) }
    }
}
