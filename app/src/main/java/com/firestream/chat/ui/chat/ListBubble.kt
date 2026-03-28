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
    onClick: () -> Unit
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
                    if (listData != null || (message.listDiff != null && !message.listDiff.deleted))
                        Modifier.clickable(onClick = onClick)
                    else Modifier
                )
                .padding(12.dp)
        ) {
            val diff = message.listDiff
            when {
                diff != null -> DiffContent(
                    diff = diff,
                    listTitle = listData?.title ?: message.content.removePrefix("📋 List: "),
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
                    timestamp = message.timestamp
                )
            }
        }
    }
}

@Composable
private fun StandardListContent(
    listData: ListData,
    textColor: Color,
    timestamp: Long
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
            Text(
                text = "Tap to view",
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
            text = if (diff.deleted) "List deleted" else "List updated",
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
            if (!diff.deleted) {
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
