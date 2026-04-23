package com.firestream.chat.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Carries the *active* dark/light flag (which honors the user's theme override),
// not just the system setting. Read this from any composable that needs to pick
// a bubble color outside the Material color roles. Default tracks the system so
// previews and any composable rendered outside FireStreamTheme stay sane.
val LocalIsDarkTheme = staticCompositionLocalOf { false }

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
    primary = FsAccent,
    onPrimary = TextOnPrimary,
    primaryContainer = FsAccentSoft,      // accent-soft: used by nav indicator pill
    onPrimaryContainer = FsAccent,
    secondary = FsTextDim,
    onSecondary = FsBg,
    secondaryContainer = FsSurface3,
    onSecondaryContainer = FsText,
    background = FsBg,
    onBackground = FsText,
    surface = FsSurface,
    onSurface = FsText,
    surfaceVariant = FsSurface,            // incoming bubble / card bg
    onSurfaceVariant = FsTextDim,
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

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}
