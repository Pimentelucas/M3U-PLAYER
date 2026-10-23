package com.m3uplayer.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Paleta escura inspirada no Stremio (a mesma da versão Windows)
object AppColors {
    val Bg = Color(0xFF0F0E17)
    val Surface = Color(0xFF1A1826)
    val Surface2 = Color(0xFF24212F)
    val Hover = Color(0xFF322D44)
    val Accent = Color(0xFF7B5BF5)
    val Text = Color(0xFFF4F3F8)
    val Muted = Color(0xFF9A96AB)
    val Line = Color(0xFF2E2A3C)
    val Danger = Color(0xFFE5484D)
}

private val scheme = darkColorScheme(
    primary = AppColors.Accent,
    onPrimary = Color.White,
    secondary = AppColors.Accent,
    secondaryContainer = AppColors.Accent,
    onSecondaryContainer = Color.White,
    background = AppColors.Bg,
    onBackground = AppColors.Text,
    surface = AppColors.Bg,
    onSurface = AppColors.Text,
    surfaceVariant = AppColors.Surface2,
    onSurfaceVariant = AppColors.Muted,
    surfaceContainer = AppColors.Surface,
    surfaceContainerLow = AppColors.Surface,
    surfaceContainerHigh = AppColors.Surface2,
    surfaceContainerHighest = AppColors.Hover,
    outline = AppColors.Line,
    outlineVariant = AppColors.Line,
    error = AppColors.Danger,
)

@Composable
fun M3UTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
