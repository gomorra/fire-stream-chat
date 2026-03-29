package com.firestream.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Subset of QUICK_REACTION_EMOJIS — skip 😮 for the compact swipe panel
private val DEFAULT_EMOJIS = QUICK_REACTION_EMOJIS.filter { it != "😮" }

@Composable
internal fun SwipeReactionPanel(
    recentEmojis: List<String>,
    currentReaction: String?,
    onEmojiSelected: (String) -> Unit,
    onPlusClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayEmojis = remember(recentEmojis) {
        val recents = recentEmojis.filter { it !in DEFAULT_EMOJIS }
        DEFAULT_EMOJIS + recents
    }

    // 125% of the inline emoji size used in chat bubbles
    val emojiSize = MaterialTheme.typography.bodyMedium.fontSize * EMOJI_INLINE_SCALE * 1.25f
    val panelWidth = (LocalConfiguration.current.screenWidthDp * 4 / 5).dp

    Surface(
        modifier = modifier.width(panelWidth),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Scrollable emoji area — takes remaining space
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(displayEmojis, key = { it }) { emoji ->
                    val isActive = emoji == currentReaction
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .then(
                                if (isActive) Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                                else Modifier
                            )
                            .clickable { onEmojiSelected(emoji) }
                            .semantics {
                                contentDescription = emoji
                                role = Role.Button
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = emoji,
                            fontSize = emojiSize.value.sp
                        )
                    }
                }
            }

            // Pinned "more" button — always visible, not affected by scroll
            VerticalDivider(
                modifier = Modifier.height(28.dp).padding(horizontal = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { onPlusClick() }
                    .semantics {
                        contentDescription = "More reactions"
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
