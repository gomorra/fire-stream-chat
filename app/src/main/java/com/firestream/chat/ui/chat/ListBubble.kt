package com.firestream.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListDiff
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.model.Message

private val DiffGreen = Color(0xFF388E3C)
private val DiffRed = Color(0xFFD32F2F)

@Composable
internal fun ListBubble(
    message: Message,
    listData: ListData?,
    isOwnMessage: Boolean,
    chatId: String,
    onClick: () -> Unit,
    onUnsharedListClick: () -> Unit = {}
) {
    val bubbleColor = if (isOwnMessage) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isOwnMessage) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val alignment = if (isOwnMessage) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    color = bubbleColor,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isOwnMessage) 16.dp else 4.dp,
                        bottomEnd = if (isOwnMessage) 4.dp else 16.dp
                    )
                )
            .then(
                    // Clickable if:
                    //  - listData is present AND the list is still shared to this chat
                    //  - OR it's a diff bubble that isn't deleted/unshared/shared-only
                    if (listData != null && chatId in listData.sharedChatIds) {
                        Modifier.clickable(onClick = onClick)
                    } else if (listData == null && message.listDiff != null
                        && !message.listDiff.deleted && !message.listDiff.unshared && !message.listDiff.shared) {
                        Modifier.clickable(onClick = onClick)
                    } else if (listData != null && chatId !in listData.sharedChatIds) {
                        // List exists but no longer shared to this chat — show snackbar on tap
                        Modifier.clickable(onClick = onUnsharedListClick)
                    } else {
                        Modifier
                    }
                )
                .padding(12.dp)
        ) {
            val diff = message.listDiff
            when {
                diff != null && diff.shared && listData != null -> SharedListContent(
                    listData = listData,
                    textColor = textColor,
                    timestamp = message.timestamp
                )
                diff != null -> DiffContent(
                    diff = diff,
                    listTitle = listData?.title ?: message.content
                        .removePrefix("📋 Shared list: ")
                        .removePrefix("📋 Removed list: ")
                        .removePrefix("📋 Deleted list: ")
                        .removePrefix("📋 List updated: ")
                        .removePrefix("📋 List: "),
                    listType = listData?.type ?: ListType.CHECKLIST,
                    textColor = textColor,
                    timestamp = message.timestamp
                )
                listData == null -> Text(
                    text = "This list was deleted",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor.copy(alpha = 0.6f),
                    fontStyle = FontStyle.Italic
                )
                else -> StandardListContent(
                    listData = listData,
                    textColor = textColor,
                    timestamp = message.timestamp,
                    isShared = chatId in listData.sharedChatIds
                )
            }
        }
    }
}

@Composable
private fun SharedListContent(
    listData: ListData,
    textColor: Color,
    timestamp: Long
) {
    Column {
        Text(
            text = "📋 Shared List",
            style = MaterialTheme.typography.titleSmall,
            color = textColor,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = textColor.copy(alpha = 0.2f))
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = when (listData.type) {
                    ListType.CHECKLIST -> Icons.Default.Checklist
                    ListType.SHOPPING -> Icons.Default.ShoppingCart
                    ListType.GENERIC -> Icons.AutoMirrored.Filled.List
                },
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = listData.title,
                style = MaterialTheme.typography.labelMedium,
                color = textColor,
                fontWeight = FontWeight.Bold
            )
        }

        if (listData.items.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            val previewItems = listData.items.take(3)
            previewItems.forEach { item ->
                val prefix = if (item.isChecked) "✓" else "○"
                val prefixColor = if (item.isChecked) DiffGreen else textColor.copy(alpha = 0.6f)
                DiffRow(
                    prefix = prefix,
                    prefixColor = prefixColor,
                    text = item.text,
                    textColor = if (item.isChecked) textColor.copy(alpha = 0.6f) else textColor
                )
            }
            val remaining = listData.items.size - previewItems.size
            if (remaining > 0) {
                Text(
                    text = "+$remaining more item${if (remaining != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tap to open",
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.5f)
            )
            Text(
                text = formatTimestamp(timestamp),
                color = textColor.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun StandardListContent(
    listData: ListData,
    textColor: Color,
    timestamp: Long,
    isShared: Boolean = true
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = when (listData.type) {
                    ListType.CHECKLIST -> Icons.Default.Checklist
                    ListType.SHOPPING -> Icons.Default.ShoppingCart
                    ListType.GENERIC -> Icons.AutoMirrored.Filled.List
                },
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = listData.title,
                style = MaterialTheme.typography.titleSmall,
                color = textColor,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        val itemCount = listData.items.size
        val checkedCount = listData.items.count { it.isChecked }
        val subtitle = when (listData.type) {
            ListType.CHECKLIST, ListType.SHOPPING -> "$checkedCount of $itemCount checked"
            ListType.GENERIC -> "$itemCount item${if (itemCount != 1) "s" else ""}"
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = textColor.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isShared) {
                Text(
                    text = "Tap to view",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.5f)
                )
            } else {
                Text(
                    text = "No longer shared",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.4f),
                    fontStyle = FontStyle.Italic
                )
            }
            Text(
                text = formatTimestamp(timestamp),
                color = textColor.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun DiffContent(
    diff: ListDiff,
    listTitle: String,
    listType: ListType,
    textColor: Color,
    timestamp: Long
) {
    Column {
        // Header: list icon + title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = when (listType) {
                    ListType.CHECKLIST -> Icons.Default.Checklist
                    ListType.SHOPPING -> Icons.Default.ShoppingCart
                    ListType.GENERIC -> Icons.AutoMirrored.Filled.List
                },
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = listTitle,
                style = MaterialTheme.typography.labelMedium,
                color = textColor,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = when {
                diff.shared -> "📋 List shared"
                diff.unshared -> "📋 List unshared"
                diff.deleted -> "List deleted"
                else -> "List updated"
            },
            style = MaterialTheme.typography.labelSmall,
            color = textColor.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = textColor.copy(alpha = 0.2f))
        Spacer(modifier = Modifier.height(6.dp))

        if (diff.titleChanged != null) {
            DiffRow(prefix = "✎", prefixColor = textColor.copy(alpha = 0.8f),
                text = "Renamed to \"${diff.titleChanged}\"", textColor = textColor)
        }
        diff.added.forEach { item ->
            DiffRow(prefix = "+", prefixColor = DiffGreen, text = item, textColor = textColor)
        }
        diff.removed.forEach { item ->
            DiffRow(prefix = "−", prefixColor = DiffRed, text = item, textColor = textColor,
                strikethrough = true)
        }
        diff.checked.forEach { item ->
            DiffRow(prefix = "✓", prefixColor = DiffGreen, text = item,
                textColor = textColor.copy(alpha = 0.6f))
        }
        diff.unchecked.forEach { item ->
            DiffRow(prefix = "○", prefixColor = textColor.copy(alpha = 0.6f), text = item,
                textColor = textColor)
        }
        diff.edited.forEach { item ->
            DiffRow(prefix = "✎", prefixColor = textColor.copy(alpha = 0.8f), text = item,
                textColor = textColor)
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!diff.deleted && !diff.unshared && !diff.shared) {
                Text(
                    text = "Tap to view",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.5f)
                )
            }
            Text(
                text = formatTimestamp(timestamp),
                color = textColor.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun DiffRow(
    prefix: String,
    prefixColor: Color,
    text: String,
    textColor: Color,
    strikethrough: Boolean = false
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 1.dp)
    ) {
        Text(
            text = prefix,
            style = MaterialTheme.typography.bodySmall,
            color = prefixColor,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            textDecoration = if (strikethrough) TextDecoration.LineThrough else TextDecoration.None
        )
    }
}
