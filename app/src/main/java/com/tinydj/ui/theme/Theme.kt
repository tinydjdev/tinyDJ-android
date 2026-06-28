package com.tinydj.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val ColorScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Body,
    background = Body,
    onBackground = Ink,
    surface = Body,
    onSurface = Ink,
    surfaceVariant = BodyDark,
)

@Composable
fun TinyDjTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography = Typography,
        content = content,
    )
}
