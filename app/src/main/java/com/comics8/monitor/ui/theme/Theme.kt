package com.comics8.monitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF121212)
private val Panel = Color(0xFF1C1C1E)
private val Accent = Color(0xFFFF4D4F)
private val OnInk = Color(0xFFF4F4F5)
private val Mute = Color(0xFFB4B4B8)

private val Colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Ink,
    onBackground = OnInk,
    surface = Panel,
    onSurface = OnInk,
    surfaceVariant = Color(0xFF2A2A2E),
    onSurfaceVariant = Mute,
    error = Accent,
)

@Composable
fun ComicsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        content = content,
    )
}
