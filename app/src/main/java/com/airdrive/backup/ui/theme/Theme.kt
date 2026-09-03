package com.airdrive.backup.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/** Persisted in SettingsStore; SYSTEM follows the device's own light/dark setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val AirDarkColors = darkColorScheme(
    primary = AirBlue,
    onPrimary = AirTextPrimary,
    primaryContainer = AirBlueDark,
    background = AirBackground,
    onBackground = AirTextPrimary,
    surface = AirSurface,
    onSurface = AirTextPrimary,
    surfaceVariant = AirSurfaceVariant,
    onSurfaceVariant = AirTextSecondary,
    error = AirError
)

private val AirLightColors = lightColorScheme(
    primary = AirBlue,
    onPrimary = AirBlueLightOn,
    primaryContainer = AirBlueDark,
    background = AirBackgroundLight,
    onBackground = AirTextPrimaryLight,
    surface = AirSurfaceLight,
    onSurface = AirTextPrimaryLight,
    surfaceVariant = AirSurfaceVariantLight,
    onSurfaceVariant = AirTextSecondaryLight,
    error = AirErrorLight
)

@Composable
fun AirDriveTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val useDark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (useDark) AirDarkColors else AirLightColors,
        typography = AirTypography,
        content = content
    )
}
