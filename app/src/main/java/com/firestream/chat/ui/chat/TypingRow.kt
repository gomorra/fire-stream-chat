package com.firestream.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firestream.chat.ui.components.TypingIndicator
import com.firestream.chat.ui.components.UserAvatar

private val AvatarSize = 24.dp
private val AvatarOverlap = 8.dp
private val AvatarToDotsGap = 6.dp
private val RingThickness = 1.dp

private fun Modifier.avatarRing(size: Dp, ringColor: Color): Modifier =
    this.size(size)
        .clip(CircleShape)
        .background(ringColor)
        .padding(RingThickness)

@Composable
internal fun TypingRow(
    avatars: List<ParticipantAvatar>,
    modifier: Modifier = Modifier,
    maxVisible: Int = 3,
) {
    if (avatars.isEmpty()) return

    val visible = avatars.take(maxVisible)
    val overflow = (avatars.size - visible.size).coerceAtLeast(0)
    val ringColor = MaterialTheme.colorScheme.surface

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(-AvatarOverlap)) {
            visible.forEach { avatar ->
                UserAvatar(
                    avatarUrl = avatar.avatarUrl,
                    localAvatarPath = avatar.localAvatarPath,
                    contentDescription = avatar.displayName,
                    icon = Icons.Default.Person,
                    size = AvatarSize,
                    modifier = Modifier.avatarRing(AvatarSize, ringColor),
                )
            }
            if (overflow > 0) {
                Box(
                    modifier = Modifier
                        .avatarRing(AvatarSize, ringColor)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+$overflow",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(AvatarToDotsGap))

        TypingIndicator(
            dotColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
