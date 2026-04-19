package com.firestream.chat.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.material.icons.Icons
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import androidx.compose.ui.graphics.Color
import com.firestream.chat.ui.theme.SentBubble
import com.firestream.chat.ui.theme.SentBubbleDark
import androidx.compose.foundation.isSystemInDarkTheme
import java.io.File
import kotlin.math.roundToInt

// Emoji size factors: emoji are always this multiple of the surrounding text size.
internal const val EMOJI_INLINE_SCALE = 1.3f  // inline emoji: 30% larger than the text
internal const val EMOJI_ONLY_SCALE   = 1.5f  // emoji-only bubble: 50% larger than the text

// Vertically centers glyphs within their line box so short bubble text sits in the
// middle of the line rather than hugging the ascender — gives the bubble a balanced
// top/bottom whitespace regardless of font metrics.
private val CenteredLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

// Holder for the 11 message-bubble callbacks. Collapsing them into one parameter
// keeps MessageBubble's explicit-parameter count at 10 (vs. 20 if inlined), which
// is below the register-allocation threshold where the Kotlin/Compose compiler
// emits `copy-cat1` moves over `Alignment.Horizontal` slots that ART's class
// verifier rejects on both arm64 and x86_64 — the "Verifier rejected class
// MessageBubbleKt" crash on chat open.
@Immutable
internal data class MessageBubbleCallbacks(
    val onDelete: (() -> Unit)?,
    val onEdit: (() -> Unit)?,
    val onReply: () -> Unit,
    val onReaction: () -> Unit,
    val onForward: () -> Unit,
    val onStar: () -> Unit = {},
    val onPin: () -> Unit = {},
    val onInfo: (() -> Unit)?,
    val onSwipeReact: () -> Unit = {},
    // Tapping the IMAGE/VIDEO/DOCUMENT bubble itself. The String is the local
    // or remote URL, but ChatScreen uses the captured `message` for full info
    // (mediaUrl + localUri + save-to-downloads), so the lambda may ignore it.
    val onImageClick: (String) -> Unit = {},
    // Tapping a thumbnail inside a LinkPreviewCard. This MUST use the URL
    // parameter — the enclosing message has no media of its own.
    val onPreviewImageClick: (String) -> Unit = {},
    val onCall: (() -> Unit)? = null,
    // Tapping the quoted-reply preview inside the bubble — jumps to the source.
    val onReplyPreviewClick: () -> Unit = {},
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalAnimationApi::class)
@Composable
internal fun MessageBubble(
    message: Message,
    isOwnMessage: Boolean,
    groupPosition: GroupPosition = GroupPosition.ALONE,
    replyToMessage: Message?,
    linkPreview: LinkPreview?,
    currentUserId: String,
    readReceiptsAllowed: Boolean = true,
    userIdToDisplayName: Map<String, String> = emptyMap(),
    callbacks: MessageBubbleCallbacks,
    uploadProgress: Float? = null,
    isHighlighted: Boolean = false,
) {
    val isDark = isSystemInDarkTheme()
    val bubbleColor = if (isOwnMessage) {
        if (isDark) SentBubbleDark else SentBubble
    } else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isOwnMessage) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant

    val showTail = remember(groupPosition) {
        groupPosition == GroupPosition.ALONE || groupPosition == GroupPosition.LAST
    }
    val bubbleShape: Shape = remember(showTail, isOwnMessage) {
        if (showTail) BubbleTailShape(isOwnMessage = isOwnMessage) else RoundedCornerShape(16.dp)
    }

    var showMenu by remember { mutableStateOf(false) }
    // Direct state for drag tracking (no coroutine per pixel); Animatable only for spring-back
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    val springAnimatable = remember { Animatable(0f) }
    var isAnimatingBack by remember { mutableStateOf(false) }
    val displayOffset = if (isAnimatingBack) springAnimatable.value else swipeOffset
    // 0 = undecided, 1 = right (reply), -1 = left (react)
    var swipeDirection by remember { mutableIntStateOf(0) }
    var hapticFired by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val groupedReactions = remember(message.reactions) {
        message.reactions.values
            .groupBy { it }
            .mapValues { it.value.size }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(displayOffset.roundToInt(), 0) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (swipeOffset > 80f) callbacks.onReply()
                        if (swipeOffset < -50f && message.deletedAt == null) callbacks.onSwipeReact()
                        swipeDirection = 0
                        hapticFired = false
                        isAnimatingBack = true
                        scope.launch {
                            springAnimatable.snapTo(swipeOffset)
                            springAnimatable.animateTo(
                                0f,
                                spring(dampingRatio = 0.6f, stiffness = 400f)
                            )
                            isAnimatingBack = false
                            swipeOffset = 0f
                        }
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        if (swipeDirection == 0) {
                            swipeDirection = if (dragAmount > 0) 1 else -1
                        }
                        val newOffset = when (swipeDirection) {
                            1 -> (swipeOffset + dragAmount).coerceIn(0f, 120f)
                            -1 -> if (message.deletedAt == null)
                                (swipeOffset + dragAmount).coerceIn(-80f, 0f)
                            else swipeOffset
                            else -> swipeOffset
                        }
                        val crossedThreshold = (swipeDirection == 1 && newOffset > 80f && swipeOffset <= 80f) ||
                                (swipeDirection == -1 && newOffset < -50f && swipeOffset >= -50f)
                        if (crossedThreshold && !hapticFired) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            hapticFired = true
                        }
                        swipeOffset = newOffset
                    }
                )
            },
        horizontalAlignment = if (isOwnMessage) Alignment.End else Alignment.Start
    ) {
        val highlightColor by animateColorAsState(
            targetValue = if (isHighlighted) MaterialTheme.colorScheme.tertiary else Color.Transparent,
            animationSpec = tween(durationMillis = if (isHighlighted) 200 else 600),
            label = "jumpToSourceHighlight"
        )
        Box {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .background(
                        color = bubbleColor,
                        shape = bubbleShape
                    )
                    .border(width = 2.dp, color = highlightColor, shape = bubbleShape)
                    .combinedClickable(
                        onClick = { if (message.type == MessageType.CALL) callbacks.onCall?.invoke() },
                        onLongClick = { showMenu = true }
                    )
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = if (showTail) 16.dp else 8.dp
                    )
            ) {
                Column {
                    if (message.deletedAt != null) {
                        Text(
                            text = "This message was deleted",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontStyle = FontStyle.Italic,
                                lineHeightStyle = CenteredLineHeight
                            ),
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
                                .clip(RoundedCornerShape(8.dp))
                                .background(color = textColor.copy(alpha = 0.1f))
                                .clickable { callbacks.onReplyPreviewClick() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = replyToMessage.content.take(80),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    lineHeightStyle = CenteredLineHeight
                                ),
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
                            // File.exists() runs on IO dispatcher to avoid blocking composition.
                            val localFileExists by produceState(initialValue = false, message.localUri) {
                                value = message.localUri != null &&
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        val f = File(message.localUri!!)
                                        f.exists() && f.isFile && f.canRead()
                                    }
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
                                        clickUrl?.let { callbacks.onImageClick(it) }
                                    }
                            ) {
                                if (imageModel != null) {
                                    AsyncImage(
                                        model = imageModel,
                                        contentDescription = "Image",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                        error = rememberVectorPainter(Icons.Default.BrokenImage)
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
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.BrokenImage,
                                            contentDescription = "Image unavailable",
                                            modifier = Modifier.size(40.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            if (message.content.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        lineHeightStyle = CenteredLineHeight
                                    ),
                                    color = textColor
                                )
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
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        lineHeightStyle = CenteredLineHeight
                                    )
                                )
                            }
                        }
                        MessageType.CALL -> {
                            val endReason = message.content // "hangup", "remote_hangup", "declined", "timeout", "error"
                            val isMissed = !isOwnMessage && endReason == "timeout"
                            val isDeclined = !isOwnMessage && endReason == "declined"
                            val callColor = if (isMissed || isDeclined) MaterialTheme.colorScheme.error else textColor
                            val callIcon = when {
                                isMissed || isDeclined -> Icons.AutoMirrored.Filled.CallMissed
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
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            lineHeightStyle = CenteredLineHeight
                                        )
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
                        MessageType.LOCATION -> {
                            LocationBubbleContent(
                                latitude = message.latitude,
                                longitude = message.longitude,
                                comment = message.content,
                                isOwnMessage = isOwnMessage
                            )
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
                                    color = textColor,
                                    style = LocalTextStyle.current.copy(
                                        lineHeightStyle = CenteredLineHeight
                                    )
                                )
                            } else {
                            val highlightColor = MaterialTheme.colorScheme.primary
                            val linkUrl = linkPreview?.url
                            val displayText = remember(message.content, message.mentions, currentUserId, userIdToDisplayName, highlightColor, linkUrl, textColor) {
                                val base = MentionFormatter.formatMentionText(
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
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    lineHeightStyle = CenteredLineHeight
                                )
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
                                    onImageClick = callbacks.onPreviewImageClick.takeIf { linkPreview.imageUrl != null }
                                )
                            }
                        }
                    }

                    if (showTail) {
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
                            AnimatedContent(
                                targetState = displayStatus,
                                transitionSpec = {
                                    (fadeIn(animationSpec = tween(200)) +
                                        scaleIn(initialScale = 0.8f, animationSpec = tween(200)))
                                        .togetherWith(fadeOut(animationSpec = tween(150)))
                                },
                                label = "receiptStatus"
                            ) { status ->
                                Icon(
                                    imageVector = when (status) {
                                        MessageStatus.SENDING -> Icons.Default.Schedule
                                        MessageStatus.SENT -> Icons.Default.Check
                                        MessageStatus.DELIVERED -> Icons.Default.DoneAll
                                        MessageStatus.READ -> Icons.Default.DoneAll
                                        MessageStatus.FAILED -> Icons.Default.ErrorOutline
                                    },
                                    contentDescription = when (status) {
                                        MessageStatus.SENDING -> "Sending"
                                        MessageStatus.SENT -> "Sent"
                                        MessageStatus.DELIVERED -> "Delivered"
                                        MessageStatus.READ -> "Read"
                                        MessageStatus.FAILED -> "Failed"
                                    },
                                    tint = when (status) {
                                        MessageStatus.READ -> MaterialTheme.colorScheme.primary
                                        MessageStatus.FAILED -> MaterialTheme.colorScheme.error
                                        else -> textColor.copy(alpha = 0.7f)
                                    },
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    }

                    } // end else (not deleted)
                }
            }

            DropdownMenu(expanded = showMenu && message.deletedAt == null, onDismissRequest = { showMenu = false }) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    FilledTonalButton(
                        onClick = { showMenu = false; callbacks.onReply() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Reply, null, modifier = Modifier.padding(end = 4.dp))
                        Text("Reply")
                    }
                    FilledTonalButton(
                        onClick = { showMenu = false; callbacks.onReaction() },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Icon(Icons.Default.EmojiEmotions, null, modifier = Modifier.padding(end = 4.dp))
                        Text("React")
                    }
                    FilledTonalButton(
                        onClick = { showMenu = false; callbacks.onForward() },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.padding(end = 4.dp))
                        Text("Forward")
                    }
                    FilledTonalButton(
                        onClick = { showMenu = false; callbacks.onStar() },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Icon(Icons.Default.Star, null, modifier = Modifier.padding(end = 4.dp))
                        Text(if (message.isStarred) "Unstar" else "Star")
                    }
                    FilledTonalButton(
                        onClick = { showMenu = false; callbacks.onPin() },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Icon(Icons.Default.PushPin, null, modifier = Modifier.padding(end = 4.dp))
                        Text(if (message.isPinned) "Unpin" else "Pin")
                    }
                    callbacks.onEdit?.let {
                        FilledTonalButton(
                            onClick = { showMenu = false; it() },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Icon(Icons.Default.Edit, null, modifier = Modifier.padding(end = 4.dp))
                            Text("Edit")
                        }
                    }
                    callbacks.onInfo?.let {
                        FilledTonalButton(
                            onClick = { showMenu = false; it() },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Icon(Icons.Default.Info, null, modifier = Modifier.padding(end = 4.dp))
                            Text("Message Info")
                        }
                    }
                    callbacks.onDelete?.let {
                        FilledTonalButton(
                            onClick = { showMenu = false; it() },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.padding(end = 4.dp))
                            Text("Delete for everyone")
                        }
                    }
                }
            }

            // Reply icon: fixed 8dp gap left of bubble, vertically centered
            SwipeActionIcon(
                offset = displayOffset,
                thresholdStart = 30f,
                thresholdRange = 50f,
                icon = Icons.AutoMirrored.Filled.Reply,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-32).dp)
            )

            // Emoji icon: fixed 8dp gap right of bubble, vertically centered
            SwipeActionIcon(
                offset = -displayOffset,
                thresholdStart = 15f,
                thresholdRange = 35f,
                icon = Icons.Default.EmojiEmotions,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 32.dp)
            )
        }

        if (groupedReactions.isNotEmpty()) {
            val reactionFontSize = MaterialTheme.typography.bodyMedium.fontSize * EMOJI_INLINE_SCALE * 1.2f
            FlowRow(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                groupedReactions.forEach { (emoji, count) ->
                    val myReaction = message.reactions[currentUserId] == emoji
                    Text(
                        text = if (count > 1) "$emoji $count" else emoji,
                        fontSize = reactionFontSize.value.sp,
                        color = if (myReaction) MaterialTheme.colorScheme.primary
                            else Color.Unspecified,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { /* handled by reaction picker */ }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SwipeActionIcon(
    offset: Float,
    thresholdStart: Float,
    thresholdRange: Float,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    if (offset > thresholdStart) {
        val progress = ((offset - thresholdStart) / thresholdRange).coerceIn(0f, 1f)
        val iconScale = 0.5f + 0.5f * progress
        Box(modifier = modifier) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = progress),
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { scaleX = iconScale; scaleY = iconScale }
            )
        }
    }
}

@Composable
private fun LocationBubbleContent(
    latitude: Double?,
    longitude: Double?,
    comment: String,
    isOwnMessage: Boolean
) {
    if (latitude == null || longitude == null) return
    val context = LocalContext.current
    val mapUrl = remember(latitude, longitude) { staticMapUrl(latitude, longitude) }
    Column(
        modifier = Modifier
            .clickable {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
                )
                context.startActivity(intent)
            }
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .height(150.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(mapUrl)
                    .addHeader("User-Agent", "FireStreamChat/1.0")
                    .build(),
                contentDescription = "Location map",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
        if (comment.isNotBlank() && comment != LOCATION_DEFAULT_CONTENT) {
            Text(
                text = comment,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Shared location",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
