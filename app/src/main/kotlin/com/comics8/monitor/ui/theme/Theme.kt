package com.comics8.monitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Shadcn Zinc Monochrome Palette
 * Pure Black, White, and Zinc Grayscale
 */
object MonochromeTheme {
    val White = Color(0xFFFFFFFF)
    val Zinc50 = Color(0xFFFAFAFA)
    val Zinc100 = Color(0xFFF4F4F5)
    val Zinc200 = Color(0xFFE4E4E7)
    val Zinc300 = Color(0xFFD4D4D8)
    val Zinc400 = Color(0xFFA1A1AA)
    val Zinc500 = Color(0xFF71717A)
    val Zinc600 = Color(0xFF52525B)
    val Zinc700 = Color(0xFF3F3F46)
    val Zinc800 = Color(0xFF27272A)
    val Zinc900 = Color(0xFF18181B)
    val Zinc950 = Color(0xFF09090B)
    val PureBlack = Color(0xFF000000)
    val Gold = Color(0xFFFFB800)
}

val MonochromeColorScheme = darkColorScheme(
    primary = MonochromeTheme.White,
    onPrimary = MonochromeTheme.PureBlack,
    primaryContainer = MonochromeTheme.Zinc800,
    onPrimaryContainer = MonochromeTheme.White,
    secondary = MonochromeTheme.Zinc200,
    onSecondary = MonochromeTheme.PureBlack,
    secondaryContainer = MonochromeTheme.Zinc900,
    onSecondaryContainer = MonochromeTheme.Zinc50,
    tertiary = MonochromeTheme.Zinc300,
    onTertiary = MonochromeTheme.PureBlack,
    background = MonochromeTheme.PureBlack,
    onBackground = MonochromeTheme.Zinc50,
    surface = MonochromeTheme.PureBlack,
    onSurface = MonochromeTheme.Zinc50,
    surfaceVariant = MonochromeTheme.Zinc900,
    onSurfaceVariant = MonochromeTheme.Zinc400,
    surfaceContainer = MonochromeTheme.Zinc950,
    surfaceContainerHigh = MonochromeTheme.Zinc900,
    surfaceContainerHighest = MonochromeTheme.Zinc800,
    outline = MonochromeTheme.Zinc700,
    outlineVariant = MonochromeTheme.Zinc800,
    error = Color(0xFFEF4444),
    onError = MonochromeTheme.PureBlack,
)

@Composable
fun ComicsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MonochromeColorScheme,
        content = content,
    )
}
