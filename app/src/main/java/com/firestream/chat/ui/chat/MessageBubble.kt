package com.firestream.chat.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.firestream.chat.domain.util.MentionParser
import coil.compose.AsyncImage
import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import androidx.compose.ui.graphics.Color
import com.firestream.chat.ui.theme.ReadReceiptBlue
import java.io.File
import kotlin.math.roundToInt

// Emoji size factors: emoji are always this multiple of the surrounding text size.
private const val EMOJI_INLINE_SCALE = 1.3f  // inline emoji: 30% larger than the text
private const val EMOJI_ONLY_SCALE   = 1.5f  // emoji-only bubble: 50% larger than the text

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun MessageBubble(
    message: Message,
    isOwnMessage: Boolean,
    replyToMessage: Message?,
    linkPreview: LinkPreview?,
    currentUserId: String,
    readReceiptsAllowed: Boolean = true,
    userIdToDisplayName: Map<String, String> = emptyMap(),
    onDeleteClick: (() -> Unit)?,
    onEditClick: (() -> Unit)?,
    onReplyClick: () -> Unit,
    onReactionClick: () -> Unit,
    onForwardClick: () -> Unit,
    onStarClick: () -> Unit = {},
    onPinClick: () -> Unit = {},
    onInfoClick: (() -> Unit)?,
    onImageClick: (String) -> Unit = {},
    onCallClick: (() -> Unit)? = null,
    uploadProgress: Float? = null
) {
    val bubbleColor = if (isOwnMessage) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isOwnMessage) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val alignment = if (isOwnMessage) Alignment.End else Alignment.Start

    var showMenu by remember { mutableStateOf(false) }
    var swipeOffset by remember { mutableFloatStateOf(0f) }

    val groupedReactions = message.reactions.values
        .groupBy { it }
        .mapValues { it.value.size }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(swipeOffset.roundToInt(), 0) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (swipeOffset > 60f) {
                            onReplyClick()
                        }
                        swipeOffset = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        if (!isOwnMessage || dragAmount > 0) {
                            swipeOffset = (swipeOffset + dragAmount).coerceIn(0f, 80f)
                        }
                    }
                )
            },
        horizontalAlignment = alignment
    ) {
        if (swipeOffset > 20f) {
            Box(modifier = Modifier.align(Alignment.Start).padding(start = 4.dp)) {
                Icon(
                    imageVector = Icons.Default.Reply,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = swipeOffset / 80f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Box {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .background(
                        color = bubbleColor,
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isOwnMessage) 16.dp else 4.dp,
                            bottomEnd = if (isOwnMessage) 4.dp else 16.dp
                        )
                    )
                    .combinedClickable(
                        onClick = { if (message.type == MessageType.CALL) onCallClick?.invoke() },
                        onLongClick = { showMenu = true }
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column {
                    if (message.deletedAt != null) {
                        Text(
                            text = "This message was deleted",
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = textColor.copy(alpha = 0.6f)
                        )
                        Text(
                            text = formatTimestamp(message.timestamp),
                            color = textColor.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.End)
                        )
                    } else {

                    if (message.isForwarded) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                tint = textColor.copy(alpha = 0.6f),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Forwarded",
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor.copy(alpha = 0.6f),
                                fontStyle = FontStyle.Italic
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    if (replyToMessage != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = textColor.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = replyToMessage.content.take(80),
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor.copy(alpha = 0.8f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontStyle = FontStyle.Italic
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    when (message.type) {
                        MessageType.IMAGE -> {
                            val aspectRatio = if (message.mediaWidth != null && message.mediaHeight != null && message.mediaHeight > 0) {
                                message.mediaWidth.toFloat() / message.mediaHeight.toFloat()
                            } else {
                                4f / 3f // fallback for old messages without dimensions
                            }

                            // Determine image source: prefer local file, fall back to remote URL.
                            // File.exists() is I/O; memoize per localUri so it only runs once per unique path.
                            val localFileExists = remember(message.localUri) {
                                message.localUri != null && File(message.localUri).exists()
                            }
                            val imageModel: Any? = when {
                                localFileExists -> File(message.localUri!!)
                                message.mediaUrl != null -> message.mediaUrl
                                else -> null
                            }

                            Box(
                                modifier = Modifier
                                    .widthIn(max = 280.dp)
                                    .then(
                                        if (aspectRatio > 0) Modifier.aspectRatio(aspectRatio, matchHeightConstraintsFirst = aspectRatio < 0.5f)
                                        else Modifier
                                    )
                                    .heightIn(min = 100.dp, max = 400.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        val clickUrl = message.localUri ?: message.mediaUrl
                                        clickUrl?.let { onImageClick(it) }
                                    }
                            ) {
                                if (imageModel != null) {
                                    AsyncImage(
                                        model = imageModel,
                                        contentDescription = "Image",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )

                                    // Upload progress overlay
                                    if (uploadProgress != null && uploadProgress < 1f) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(8.dp)
                                                .size(28.dp)
                                                .background(
                                                    color = Color.Black.copy(alpha = 0.5f),
                                                    shape = CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                progress = { uploadProgress },
                                                modifier = Modifier.size(20.dp),
                                                color = Color.White,
                                                strokeWidth = 2.dp
                                            )
                                        }
                                    }
                                } else {
                                    // No image source available yet — show loading placeholder
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(color = textColor, modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                        MessageType.VOICE -> {
                            VoiceMessagePlayer(
                                mediaUrl = message.mediaUrl,
                                durationSeconds = message.duration ?: 0,
                                textColor = textColor
                            )
                        }
                        MessageType.DOCUMENT -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AttachFile,
                                    contentDescription = null,
                                    tint = textColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = message.content,
                                    color = textColor,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        MessageType.CALL -> {
                            val endReason = message.content // "hangup", "remote_hangup", "declined", "timeout", "error"
                            val isMissed = !isOwnMessage && endReason == "timeout"
                            val isDeclined = !isOwnMessage && endReason == "declined"
                            val callColor = if (isMissed || isDeclined) MaterialTheme.colorScheme.error else textColor
                            val callIcon = when {
                                isMissed || isDeclined -> Icons.Default.CallMissed
                                else -> Icons.Default.Call
                            }
                            val callLabel = when {
                                isOwnMessage && endReason == "timeout" -> "No answer"
                                isOwnMessage && endReason == "declined" -> "Declined"
                                isOwnMessage -> "Outgoing call"
                                isMissed -> "Missed call"
                                isDeclined -> "Declined"
                                else -> "Incoming call"
                            }
                            val durationSeconds = message.duration ?: 0
                            val callDetail = when {
                                durationSeconds > 0 -> {
                                    val m = durationSeconds / 60
                                    val s = durationSeconds % 60
                                    if (m > 0) "${m}m ${s}s" else "${s}s"
                                }
                                else -> null
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = callIcon,
                                    contentDescription = null,
                                    tint = callColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = callLabel,
                                        color = callColor,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (callDetail != null) {
                                        Text(
                                            text = callDetail,
                                            color = callColor.copy(alpha = 0.7f),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Text(
                                        text = formatTimestamp(message.timestamp),
                                        color = callColor.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                        else -> {
                            val isEmojiOnlyMsg = remember(message.content) { isEmojiOnly(message.content) }
                            if (isEmojiOnlyMsg) {
                                val baseSize = MaterialTheme.typography.bodyMedium.fontSize
                                val emojiOnlySize = baseSize * EMOJI_ONLY_SCALE
                                val sized = remember(message.content, emojiOnlySize, message.emojiSizes) {
                                    addEmojiSpans(message.content, emojiOnlySize, message.emojiSizes)
                                }
                                Text(
                                    text = sized,
                                    fontSize = emojiOnlySize,
                                    lineHeight = emojiOnlySize * 1.2f,
                                    color = textColor
                                )
                            } else {
                            val highlightColor = MaterialTheme.colorScheme.primary
                            val linkUrl = linkPreview?.url
                            val displayText = remember(message.content, message.mentions, currentUserId, userIdToDisplayName, highlightColor, linkUrl, textColor) {
                                val base = MentionParser.formatMentionText(
                                    text = message.content,
                                    mentions = message.mentions,
                                    currentUserId = currentUserId,
                                    highlightColor = highlightColor,
                                    userIdToDisplayName = userIdToDisplayName
                                )
                                if (linkUrl != null) {
                                    val idx = base.text.indexOf(linkUrl)
                                    if (idx >= 0) buildAnnotatedString {
                                        append(base.subSequence(0, idx))
                                        withLink(LinkAnnotation.Url(
                                            url = linkUrl,
                                            styles = TextLinkStyles(SpanStyle(
                                                fontSize = 12.sp,
                                                color = textColor.copy(alpha = 0.85f),
                                                textDecoration = TextDecoration.Underline
                                            ))
                                        )) { append(linkUrl) }
                                        if (idx + linkUrl.length < base.length)
                                            append(base.subSequence(idx + linkUrl.length, base.length))
                                    } else base
                                } else base
                            }
                            val emojiInlineSize = MaterialTheme.typography.bodyMedium.fontSize * EMOJI_INLINE_SCALE
                            val displayTextWithEmojis = remember(displayText, emojiInlineSize, message.emojiSizes) {
                                addEmojiSpans(displayText, emojiInlineSize, message.emojiSizes)
                            }
                            Text(
                                text = displayTextWithEmojis,
                                color = textColor,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            } // end non-emoji-only branch
                            if (message.editedAt != null) {
                                Text(
                                    text = "(edited)",
                                    color = textColor.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (linkPreview != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                LinkPreviewCard(
                                    preview = linkPreview,
                                    textColor = textColor,
                                    onImageClick = onImageClick.takeIf { linkPreview.imageUrl != null }
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        if (message.type != MessageType.CALL) {
                            Text(
                                text = formatTimestamp(message.timestamp),
                                color = textColor.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (isOwnMessage) {
                            Spacer(modifier = Modifier.width(4.dp))
                            val displayStatus = if (!readReceiptsAllowed && message.status == MessageStatus.READ) {
                                MessageStatus.DELIVERED
                            } else {
                                message.status
                            }
                            Icon(
                                imageVector = when (displayStatus) {
                                    MessageStatus.SENDING -> Icons.Default.Schedule
                                    MessageStatus.SENT -> Icons.Default.Check
                                    MessageStatus.DELIVERED -> Icons.Default.DoneAll
                                    MessageStatus.READ -> Icons.Default.DoneAll
                                    MessageStatus.FAILED -> Icons.Default.ErrorOutline
                                },
                                contentDescription = when (displayStatus) {
                                    MessageStatus.SENDING -> "Sending"
                                    MessageStatus.SENT -> "Sent"
                                    MessageStatus.DELIVERED -> "Delivered"
                                    MessageStatus.READ -> "Read"
                                    MessageStatus.FAILED -> "Failed"
                                },
                                tint = when (displayStatus) {
                                    MessageStatus.READ -> ReadReceiptBlue
                                    MessageStatus.FAILED -> MaterialTheme.colorScheme.error
                                    else -> textColor.copy(alpha = 0.7f)
                                },
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    } // end else (not deleted)
                }
            }

            DropdownMenu(expanded = showMenu && message.deletedAt == null, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Reply") },
                    leadingIcon = { Icon(Icons.Default.Reply, null) },
                    onClick = { showMenu = false; onReplyClick() }
                )
                DropdownMenuItem(
                    text = { Text("React") },
                    onClick = { showMenu = false; onReactionClick() }
                )
                DropdownMenuItem(
                    text = { Text("Forward") },
                    leadingIcon = { Icon(Icons.Default.Share, null) },
                    onClick = { showMenu = false; onForwardClick() }
                )
                DropdownMenuItem(
                    text = { Text(if (message.isStarred) "Unstar" else "Star") },
                    onClick = { showMenu = false; onStarClick() }
                )
                DropdownMenuItem(
                    text = { Text(if (message.isPinned) "Unpin" else "Pin") },
                    onClick = { showMenu = false; onPinClick() }
                )
                onEditClick?.let {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = { showMenu = false; it() }
                    )
                }
                onInfoClick?.let {
                    DropdownMenuItem(
                        text = { Text("Message Info") },
                        leadingIcon = { Icon(Icons.Default.Info, null) },
                        onClick = { showMenu = false; it() }
                    )
                }
                onDeleteClick?.let {
                    DropdownMenuItem(
                        text = { Text("Delete for everyone", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; it() }
                    )
                }
            }
        }

        if (groupedReactions.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                groupedReactions.forEach { (emoji, count) ->
                    val myReaction = message.reactions[currentUserId] == emoji
                    AssistChip(
                        onClick = { /* handled by reaction picker */ },
                        label = {
                            Text(
                                text = if (count > 1) "$emoji $count" else emoji,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = MaterialTheme.typography.labelSmall.fontSize * 1.2f
                                )
                            )
                        },
                        modifier = Modifier.height(34.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (myReaction) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                        )
                    )
                }
            }
        }
    }
}
