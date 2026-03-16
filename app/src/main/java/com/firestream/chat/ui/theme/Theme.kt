package com.firestream.chat.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Immutable
data class FireStreamColors(
    val sentBubble: Color,
    val receivedBubble: Color,
    val receivedBubbleBorder: Color,
    val onlineGreen: Color,
    val readReceiptBlue: Color,
)

val LocalFireStreamColors = staticCompositionLocalOf {
    FireStreamColors(
        sentBubble = SentBubble,
        receivedBubble = ReceivedBubble,
        receivedBubbleBorder = ReceivedBubbleBorderLight,
        onlineGreen = OnlineGreen,
        readReceiptBlue = ReadReceiptBlue,
    )
}

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
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
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    outline = OutlineLight,
    error = ErrorRed,
    onError = TextOnPrimary,
)

private val DarkColorScheme = darkColorScheme(
    primary = FireOrange,
    onPrimary = TextOnPrimary,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = CharcoalLight,
    onSecondary = TextOnPrimary,
    secondaryContainer = Charcoal,
    onSecondaryContainer = OnSurfaceDark,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = ReceivedBubbleDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    outline = OutlineDark,
    error = ErrorRed,
    onError = TextOnPrimary,
)

private val LightFireStreamColors = FireStreamColors(
    sentBubble = SentBubble,
    receivedBubble = ReceivedBubble,
    receivedBubbleBorder = ReceivedBubbleBorderLight,
    onlineGreen = OnlineGreen,
    readReceiptBlue = ReadReceiptBlue,
)

private val DarkFireStreamColors = FireStreamColors(
    sentBubble = SentBubbleDark,
    receivedBubble = ReceivedBubbleDark,
    receivedBubbleBorder = ReceivedBubbleBorderDark,
    onlineGreen = OnlineGreen,
    readReceiptBlue = ReadReceiptBlue,
)

@Composable
fun FireStreamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val fireStreamColors = if (darkTheme) DarkFireStreamColors else LightFireStreamColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalFireStreamColors provides fireStreamColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}
