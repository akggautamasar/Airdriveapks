package com.airdrive.backup.ui.screens

import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxHeight

/** Fallback for reusable viewer composables that are not themselves Row/Column scopes. */
private fun Modifier.weight(value: Float): Modifier = fillMaxHeight(value.coerceIn(0.1f, 0.9f))
