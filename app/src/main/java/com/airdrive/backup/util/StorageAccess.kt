package com.airdrive.backup.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Everything AirDrive needs to read the whole phone instead of a hand-picked SAF folder.
 *
 * On Android 11+ that means MANAGE_EXTERNAL_STORAGE ("All files access"), which the user has to
 * grant from a system settings page — it cannot be granted by a normal runtime dialog. On
 * Android 8-10 plain READ_EXTERNAL_STORAGE plus requestLegacyExternalStorage is enough.
 *
 * Note that even with All files access, /Android/data and /Android/obb stay unreadable on
 * Android 11+; the scanner skips them for that reason. /Android/media (where WhatsApp and
 * friends keep their media on newer Android) is readable and is *not* skipped.
 */
object StorageAccess {

    fun hasFullAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** True when the grant lives on a settings page rather than a runtime permission dialog. */
    val grantedFromSettingsScreen: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Intents to open the All files access page, most specific first. Some OEM builds do not
     * resolve the per-app variant, so the caller should try each until one starts.
     */
    fun allFilesAccessIntents(context: Context): List<Intent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return listOf(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    }

    /**
     * Opens the first All files access page that actually resolves on this device. Returns false
     * when none of them do, so the caller can fall back to telling the user where to look.
     */
    fun openAllFilesAccess(context: Context): Boolean {
        for (intent in allFilesAccessIntents(context)) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) {
                // OEM ROM without this settings page; try the next, less specific candidate.
            }
        }
        return false
    }

    /**
     * Root folders to walk: internal storage (/storage/emulated/0) and, optionally, every
     * removable volume. Derived from getExternalFilesDirs, which is the only reliable public
     * way to learn the mount points of SD cards and USB drives.
     */
    @Suppress("DEPRECATION")
    fun scanRoots(context: Context, includeRemovable: Boolean): List<File> {
        val roots = LinkedHashMap<String, File>()

        val primary = Environment.getExternalStorageDirectory()
        if (primary != null && primary.isDirectory) roots[primary.absolutePath] = primary

        if (includeRemovable) {
            for (dir in ContextCompat.getExternalFilesDirs(context, null)) {
                val path = dir?.absolutePath ?: continue
                val marker = "/Android/data/"
                if (!path.contains(marker)) continue
                val volumeRoot = File(path.substringBefore(marker))
                if (volumeRoot.isDirectory && volumeRoot.canRead()) {
                    roots.putIfAbsent(volumeRoot.absolutePath, volumeRoot)
                }
            }
        }

        return roots.values.toList()
    }

    /** Short human-readable summary for the storage-access screen, e.g. "Internal storage + 1 card". */
    fun describeRoots(context: Context, includeRemovable: Boolean): String {
        val roots = scanRoots(context, includeRemovable)
        if (roots.isEmpty()) return "No readable storage found"
        val extra = roots.size - 1
        return when {
            extra <= 0 -> "Internal storage (${roots.first().absolutePath})"
            extra == 1 -> "Internal storage + 1 removable card"
            else -> "Internal storage + $extra removable volumes"
        }
    }
}
