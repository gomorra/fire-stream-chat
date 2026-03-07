package com.firestream.chat.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = FireOrange,
    onPrimary = TextOnPrimary,
    primaryContainer = FireOrangeLight,
    onPrimaryContainer = CharcoalDark,
    secondary = Charcoal,
    onSecondary = TextOnPrimary,
    secondaryContainer = CharcoalLight,
    onSecondaryContainer = TextOnPrimary,
    background = BackgroundLight,
    onBackground = TextPrimary,
    surface = SurfaceLight,
    onSurface = TextPrimary,
    surfaceVariant = ReceivedBubble,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    onError = TextOnPrimary,
)

private val DarkColorScheme = darkColorScheme(
    primary = FireOrange,
    onPrimary = TextOnPrimary,
    primaryContainer = FireOrangeDark,
    onPrimaryContainer = TextOnPrimary,
    secondary = CharcoalLight,
    onSecondary = TextOnPrimary,
    secondaryContainer = Charcoal,
    onSecondaryContainer = TextPrimaryDark,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = ReceivedBubbleDark,
    onSurfaceVariant = TextSecondaryDark,
    error = ErrorRed,
    onError = TextOnPrimary,
)

@Composable
fun FireStreamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
