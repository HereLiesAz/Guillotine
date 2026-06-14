@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.hereliesaz.guillotine.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Guillotine is dark-only by design (a video editor surface).
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

/**
 * App theme built on the **Material 3 Expressive** design system. We keep our dark
 * color scheme and let shapes, motion and typography default to their expressive
 * variants.
 */
@Composable
fun GuillotineTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = GuillotineColorScheme,
        content = content,
    )
}
