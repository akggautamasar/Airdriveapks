package com.airdrive.backup.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

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

@Composable
fun AirDriveTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AirDarkColors,
        typography = AirTypography,
        content = content
    )
}
