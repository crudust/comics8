package com.comics8.monitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Catppuccin Mocha Color Palette
 * Official soothing pastel dark theme palette
 */
object CatppuccinMocha {
    val Rosewater = Color(0xFFF5E0DC)
    val Flamingo = Color(0xFFF2CDCD)
    val Pink = Color(0xFFF5C2E7)
    val Mauve = Color(0xFFCBA6F7)
    val Red = Color(0xFFF38BA8)
    val Maroon = Color(0xFFEBA0AC)
    val Peach = Color(0xFFFAB387)
    val Yellow = Color(0xFFF9E2AF)
    val Green = Color(0xFFA6E3A1)
    val Teal = Color(0xFF94E2D5)
    val Sky = Color(0xFF89DCEB)
    val Sapphire = Color(0xFF74C7EC)
    val Blue = Color(0xFF89B4FA)
    val Lavender = Color(0xFFB4BEFE)
    val Text = Color(0xFFCDD6F4)
    val Subtext1 = Color(0xFFBAC2DE)
    val Subtext0 = Color(0xFFA6ADC8)
    val Overlay2 = Color(0xFF9399B2)
    val Overlay1 = Color(0xFF7F849C)
    val Overlay0 = Color(0xFF6C7086)
    val Surface2 = Color(0xFF585B70)
    val Surface1 = Color(0xFF45475A)
    val Surface0 = Color(0xFF313244)
    val Base = Color(0xFF1E1E2E)
    val Mantle = Color(0xFF181825)
    val Crust = Color(0xFF11111B)
}

val CatppuccinMochaColorScheme = darkColorScheme(
    primary = CatppuccinMocha.Mauve,
    onPrimary = Color.Black,
    primaryContainer = CatppuccinMocha.Surface0,
    onPrimaryContainer = CatppuccinMocha.Mauve,
    secondary = CatppuccinMocha.Blue,
    onSecondary = Color.Black,
    secondaryContainer = CatppuccinMocha.Mantle,
    onSecondaryContainer = CatppuccinMocha.Text,
    tertiary = CatppuccinMocha.Lavender,
    onTertiary = Color.Black,
    background = Color.Black,
    onBackground = CatppuccinMocha.Text,
    surface = Color.Black,
    onSurface = CatppuccinMocha.Text,
    surfaceVariant = CatppuccinMocha.Mantle,
    onSurfaceVariant = CatppuccinMocha.Subtext0,
    surfaceContainer = CatppuccinMocha.Crust,
    surfaceContainerHigh = CatppuccinMocha.Mantle,
    surfaceContainerHighest = CatppuccinMocha.Surface0,
    outline = CatppuccinMocha.Surface1,
    outlineVariant = CatppuccinMocha.Surface0,
    error = CatppuccinMocha.Red,
    onError = Color.Black,
)

@Composable
fun ComicsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CatppuccinMochaColorScheme,
        content = content,
    )
}
