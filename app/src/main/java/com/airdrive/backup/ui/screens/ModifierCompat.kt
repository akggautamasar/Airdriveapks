package com.airdrive.backup.ui.screens

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Modifier

/** Fallback for reusable viewer composables that are not themselves Row/Column scopes. */
fun Modifier.weight(value: Float): Modifier = fillMaxHeight(value.coerceIn(0.1f, 0.9f))
