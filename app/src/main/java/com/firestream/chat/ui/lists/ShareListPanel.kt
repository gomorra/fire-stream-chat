package com.firestream.chat.ui.lists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.User
import com.firestream.chat.ui.components.ChatPickerLabels
import com.firestream.chat.ui.components.ChatPickerOverlay

/**
 * "Share list to…" — the chat picker, opened over the Lists tab.
 *
 * The same panel the share target and message forwarding use, so sending a list
 * into a chat looks like sending anything else into a chat; it replaced a bare
 * `AlertDialog` list, and brought multi-select with it. Opening, closing and
 * picking belong to [ChatPickerOverlay]; what is left here is the list preview.
 *
 * This tab is a page of `MainScreen`'s pager, which would read a sideways drag
 * on the panel as a tab swipe. That is settled where the pager is — the host
 * raises `onOverlayVisibleChange` and `MainScreen` locks it — not by this panel
 * swallowing gestures it does not own.
 */
@Composable
internal fun ShareListPanel(
    target: ListData?,
    chats: List<Chat>,
    currentUserId: String,
    participants: Map<String, User>,
    onDismiss: () -> Unit,
    onShare: (list: ListData, targets: List<Chat>) -> Unit,
) {
    ChatPickerOverlay(
        target = target,
        chats = chats,
        currentUserId = currentUserId,
        participants = participants,
        labels = ChatPickerLabels(
            title = "Share list to…",
            emptyLabel = "No chats to share with",
        ),
        onDismiss = onDismiss,
        onSend = onShare,
    ) { listData ->
        SharedListPreview(listData)
    }
}

/** The list about to be shared, described the way its row in the tab describes it. */
@Composable
private fun SharedListPreview(listData: ListData) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(16.dp),
    ) {
        Icon(
            imageVector = listData.typeIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = listData.title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listData.summaryLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
