package com.epic.souyaku

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SouyakuDarkColors = darkColorScheme(
    primary = Color(0xFF67D9FF),
    onPrimary = Color(0xFF001F29),
    secondary = Color(0xFF9E91FF),
    tertiary = Color(0xFF78F2D0),
    background = Color(0xFF06080E),
    onBackground = Color(0xFFE7F6FC),
    surface = Color(0xFF0D111B),
    onSurface = Color(0xFFE7F6FC),
    surfaceVariant = Color(0xFF151B28),
    onSurfaceVariant = Color(0xFFB9C7D2),
    error = Color(0xFFFF6E7D),
)

@Composable
fun SouyakuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SouyakuDarkColors, content = content)
}
