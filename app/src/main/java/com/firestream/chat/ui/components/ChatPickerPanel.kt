package com.firestream.chat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import androidx.compose.runtime.remember
import com.firestream.chat.domain.model.User

/**
 * The chat picker: one panel behind every "send this to a chat" flow.
 *
 * ### Why it is shared
 *
 * Picking chats to send something to happens in three places — an incoming
 * share intent (`ui/share/SharePickerScreen`), forwarding a message
 * (`ui/chat/ForwardMessagePanel`) and sharing a list (`ui/lists/ShareListPanel`)
 * — and the three had drifted into three different controls: a full screen with
 * search and multi-select for the share intent, and a bare `AlertDialog` list
 * for the other two. Same question, three answers. This is the one answer: the
 * host supplies the [preview] of *what* is being sent and the callbacks for
 * *sending* it; everything else — the bar, the search field, the rows, the
 * selection affordance, the send button — lives here.
 *
 * ### The layout, top to bottom
 *
 * The preview takes all the space the chat list does not: the thing being sent
 * is what the user is deciding *about*, so it gets the room, and the list is
 * held to roughly three and a half rows and scrolls. Search sits between them,
 * directly above the rows it filters.
 *
 * ### Searching is the panel's, not the host's
 *
 * [ChatPickerState.chats] is every chat the host can send to; the panel filters
 * them by [ChatPickerState.searchQuery] itself. A shared control whose
 * correctness depends on each host remembering to call the same free function
 * is the shape that produced the three-way divergence in the first place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatPickerPanel(
    state: ChatPickerState,
    callbacks: ChatPickerCallbacks,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
    preview: @Composable BoxScope.() -> Unit,
) {
    val visibleChats = remember(state.chats, state.searchQuery, state.currentUserId, state.participants) {
        state.chats.filterChatsByName(state.searchQuery, state.currentUserId, state.participants)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.title)
                        if (state.selectedChatIds.isNotEmpty()) {
                            Text(
                                text = "${state.selectedChatIds.size} chat(s) selected",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        floatingActionButton = {
            if (state.selectedChatIds.isNotEmpty()) {
                FloatingActionButton(
                    onClick = callbacks.onSend,
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(CHAT_PICKER_SEND_TAG)
                ) {
                    if (state.isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        },
        snackbarHost = snackbarHost
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
                content = preview
            )

            HorizontalDivider()

            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = callbacks.onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search chats") },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                },
                singleLine = true
            )

            // Held to ~3.5 rows so the preview above keeps the generous share.
            if (visibleChats.isEmpty()) {
                Box(
                    modifier = Modifier
                        .heightIn(max = CHAT_LIST_MAX_HEIGHT)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (state.searchQuery.isBlank()) state.emptyLabel
                        else "No results for \"${state.searchQuery}\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = CHAT_LIST_MAX_HEIGHT)) {
                    items(visibleChats, key = { it.id }) { chat ->
                        ChatPickerRow(
                            chat = chat,
                            currentUserId = state.currentUserId,
                            participants = state.participants,
                            isSelected = chat.id in state.selectedChatIds,
                            onClick = { callbacks.onToggleChat(chat.id) }
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                    }
                }
            }
        }
    }
}

/** Test tag on the send button, so a UI test can find it without matching an icon. */
internal const val CHAT_PICKER_SEND_TAG = "chat_picker_send"

private val CHAT_LIST_MAX_HEIGHT = 260.dp

/** Everything [ChatPickerPanel] draws. [chats] is every chat the host can send to. */
@Immutable
internal data class ChatPickerState(
    val title: String,
    val chats: List<Chat>,
    val currentUserId: String,
    val participants: Map<String, User> = emptyMap(),
    val selectedChatIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val isSending: Boolean = false,
    val emptyLabel: String = "No chats yet",
)

/**
 * Bundled rather than passed one by one — see the param-count ceiling in
 * `docs/GOTCHAS.md` and `MessageBubbleCallbacks`.
 */
@Immutable
internal data class ChatPickerCallbacks(
    val onBack: () -> Unit,
    val onSearchQueryChange: (String) -> Unit,
    val onToggleChat: (String) -> Unit,
    val onSend: () -> Unit,
)

@Composable
private fun ChatPickerRow(
    chat: Chat,
    currentUserId: String,
    participants: Map<String, User>,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    // One scan of the participant list, shared by the name and the avatar — the
    // row re-runs on every keystroke in the search field above it.
    val profile = participants[chat.otherParticipantId(currentUserId)]
    val displayName = chat.pickerDisplayName(profile)
    val avatarUrl = chat.avatarUrl ?: profile?.avatarUrl
    val localAvatarPath = chat.localAvatarPath ?: profile?.localAvatarPath

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            avatarUrl = avatarUrl,
            contentDescription = displayName,
            icon = when (chat.type) {
                ChatType.BROADCAST -> Icons.Default.Campaign
                ChatType.GROUP -> Icons.Default.Group
                else -> Icons.Default.Person
            },
            size = 48.dp,
            modifier = Modifier.size(48.dp),
            localAvatarPath = localAvatarPath
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            chat.lastMessage?.let { msg ->
                Text(
                    text = msg.content.take(50),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Checkbox(checked = isSelected, onCheckedChange = { onClick() })
    }
}
