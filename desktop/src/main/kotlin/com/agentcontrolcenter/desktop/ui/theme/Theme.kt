package com.agentcontrolcenter.desktop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Indigo = Color(0xFF6750A4)
private val IndigoLight = Color(0xFFD0BCFF)
private val Teal = Color(0xFF006A6A)

private val DarkScheme = darkColorScheme(
    primary = IndigoLight,
    secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFF7FDACF),
    background = Color(0xFF1C1B1F),
    surface = Color(0xFF211F26),
    surfaceVariant = Color(0xFF49454F)
)

private val LightScheme = lightColorScheme(
    primary = Indigo,
    secondary = Color(0xFF625B71),
    tertiary = Teal,
    background = Color(0xFFFDF8FF),
    surface = Color(0xFFFDF8FF),
    surfaceVariant = Color(0xFFE7E0EC)
)

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content
    )
}
