package io.github.astromg01.launcher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF77E68C),
    onPrimary = Color(0xFF07150B),
    secondary = Color(0xFF70D7FF),
    background = Color(0xFF0B0F14),
    surface = Color(0xFF111820),
    surfaceVariant = Color(0xFF18212B)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B2C),
    secondary = Color(0xFF006782)
)

@Composable
fun AstraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
