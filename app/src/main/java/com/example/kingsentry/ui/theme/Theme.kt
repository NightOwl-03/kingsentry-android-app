package com.example.kingsentry.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = ForestGreen,
    onPrimary = HighContrastWhite,
    secondary = SafetyOrange,
    onSecondary = HighContrastBlack,
    background = HighContrastBlack,
    surface = DarkGrey,
    onBackground = HighContrastWhite,
    onSurface = HighContrastWhite
)

private val LightColorScheme = lightColorScheme(
    primary = ForestGreen,
    onPrimary = HighContrastWhite,
    secondary = SafetyOrange,
    onSecondary = HighContrastWhite,
    background = HighContrastWhite,
    surface = LightGrey,
    onBackground = HighContrastBlack,
    onSurface = HighContrastBlack
)

@Composable
fun KingSentryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // We disable dynamic color to maintain strict high-contrast agricultural UX
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}