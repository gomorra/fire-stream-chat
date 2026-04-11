package com.firestream.chat.ui.chatlist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.ui.components.TypingIndicator
import com.firestream.chat.ui.components.UserAvatar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
fun ChatListItem(
    chat: Chat,
    currentUserId: String,
    contacts: Map<String, Contact> = emptyMap(),
    isRecipientOnline: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onAvatarClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val recipientId = if (chat.type == ChatType.INDIVIDUAL) {
        chat.participants.firstOrNull { it != currentUserId }
    } else null
    val displayName = recipientId
        ?.let { contacts[it]?.displayName?.takeIf { n -> n.isNotBlank() } }
        ?: chat.name ?: "Chat"
    val avatarUrl = recipientId?.let { contacts[it]?.avatarUrl } ?: chat.avatarUrl
    val localAvatarPath = recipientId?.let { contacts[it]?.localAvatarPath } ?: chat.localAvatarPath
    val isMuted = remember(chat.muteUntil) {
        chat.muteUntil == Long.MAX_VALUE ||
            (chat.muteUntil > 0 && chat.muteUntil > System.currentTimeMillis())
    }

    // The avatar is a SIBLING of the row's clickable area, not a descendant.
    // Nesting Box.clickable inside Row.combinedClickable causes both handlers
    // to fire on the same tap (Compose's nested-clickable hit testing isn't
    // reliable for this combination), so a tap on the avatar would open the
    // chat AND set fullscreenAvatar simultaneously, masking the viewer behind
    // the navigation transition.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with optional online dot overlay — owns its own clickable.
        // Group + broadcast chats have recipientId == null, so the avatar
        // resolution above falls through to chat.avatarUrl/localAvatarPath
        // and `hasAvatarImage` is true for any group with a custom picture.
        // Image avatar → open fullscreen viewer.
        // Icon avatar (no image) → fall through to the row click and open
        // the chat, so the avatar slot isn't a dead zone.
        val hasAvatarImage = avatarUrl != null || localAvatarPath != null
        val avatarModifier = if (hasAvatarImage && onAvatarClick != null) {
            Modifier.clickable(onClick = onAvatarClick)
        } else {
            Modifier.clickable(onClick = onClick)
        }
        Box(
            contentAlignment = Alignment.BottomEnd,
            modifier = avatarModifier
        ) {
            UserAvatar(
                avatarUrl = avatarUrl,
                contentDescription = displayName,
                icon = when (chat.type) {
                    ChatType.BROADCAST -> Icons.Default.Campaign
                    ChatType.GROUP -> Icons.Default.Group
                    else -> Icons.Default.Person
                },
                size = 52.dp,
                modifier = Modifier.size(52.dp),
                localAvatarPath = localAvatarPath
            )
            if (isRecipientOnline) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF4CAF50), // green
                    modifier = Modifier
                        .size(14.dp)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                ) {}
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Row body (name, preview, time, badge) owns the chat-open tap and
        // long-press menu. Avatar taps never reach this region.
        Row(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Name + message preview (takes all remaining space)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (chat.isPinned) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                val someoneElseTyping = chat.typingUserIds.any { it != currentUserId }
                if (someoneElseTyping) {
                    TypingIndicator(
                        dotColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    val lastMsg = chat.lastMessage
                    val previewText = lastMsg?.content?.takeIf { it.isNotBlank() }?.let { content ->
                        if (lastMsg.senderId == currentUserId) "You: $content" else content
                    }
                    if (previewText != null) {
                        Text(
                            text = previewText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right column: time + unread badge
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                chat.lastMessage?.timestamp?.let { timestamp ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatTime(timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isMuted) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                                contentDescription = "Muted",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
                AnimatedContent(
                    targetState = chat.unreadCount,
                    transitionSpec = {
                        (scaleIn(
                            initialScale = 0.8f,
                            animationSpec = spring(
                                dampingRatio = 0.4f,
                                stiffness = Spring.StiffnessMedium
                            )
                        ) + fadeIn()).togetherWith(scaleOut(targetScale = 0.8f) + fadeOut())
                    },
                    label = "unreadBadge"
                ) { count ->
                    if (count > 0) {
                        Surface(
                            shape = CircleShape,
                            color = if (isMuted) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (count > 99) "99+" else count.toString(),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
private val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

private fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val oneDay = 24 * 60 * 60 * 1000L

    return when {
        diff < oneDay -> timeFormat.format(Date(timestamp))
        diff < 7 * oneDay -> dayFormat.format(Date(timestamp))
        else -> dateFormat.format(Date(timestamp))
    }
}
