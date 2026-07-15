package com.hereliesaz.guillotine.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GuillotineColorScheme = darkColorScheme(
    primary = Red500,
    onPrimary = White,
    secondary = Red600,
    onSecondary = White,
    background = Black,
    onBackground = White,
    surface = Neutral950,
    onSurface = Neutral300,
    surfaceVariant = Neutral900,
    onSurfaceVariant = Neutral400,
    outline = Neutral800,
    error = Red500,
)

@Composable
fun GuillotineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GuillotineColorScheme,
        typography = GuillotineTypography,
        content = content,
    )
}
