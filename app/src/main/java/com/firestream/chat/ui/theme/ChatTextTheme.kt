package com.firestream.chat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.sp

/**
 * Applies the user's chat font size to [content].
 *
 * Message text and the composer both read `bodyMedium`, so this replaces that
 * one style and leaves the rest of the scale alone. The line height keeps the
 * ratio `Type.kt` gives `bodyMedium`.
 */
@Composable
fun ChatTextTheme(fontSizeSp: Float, content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    val typography = remember(base, fontSizeSp) {
        val body = base.bodyMedium
        val lineHeightRatio = body.lineHeight.value / body.fontSize.value
        base.copy(
            bodyMedium = body.copy(
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp * lineHeightRatio).sp,
            )
        )
    }
    MaterialTheme(typography = typography, content = content)
}
