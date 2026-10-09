package com.firestream.chat.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How a call control with two states is drawn. Lit means the thing is out of its resting state:
 * the camera is on, the microphone is muted, the audio plays somewhere other than the earpiece.
 */
@Immutable
internal data class CallControlColors(
    val background: Color,
    val icon: Color,
    val litBackground: Color,
    val litIcon: Color,
) {
    fun background(lit: Boolean) = if (lit) litBackground else background
    fun icon(lit: Boolean) = if (lit) litIcon else icon

    companion object {
        /** The colours of the app theme, for a control outside the stage. */
        @Composable
        fun themed() = CallControlColors(
            background = MaterialTheme.colorScheme.surfaceVariant,
            icon = MaterialTheme.colorScheme.onSurface,
            litBackground = MaterialTheme.colorScheme.secondary,
            litIcon = MaterialTheme.colorScheme.onSecondary,
        )
    }
}

@Composable
internal fun CallControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = 64.dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .graphicsLayer { alpha = if (enabled) 1f else DISABLED_ALPHA }
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(size * 0.45f)
        )
    }
}

private const val DISABLED_ALPHA = 0.38f
