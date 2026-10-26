package com.usaginoyami.cloud.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val UsagiRed = Color(0xFFE53935)

private val Colors = darkColorScheme(
    primary = UsagiRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5A1414),
    onPrimaryContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF151515),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFFB5B5B5),
    error = Color(0xFFFF6B6B),
)

@Composable
fun UsagiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
