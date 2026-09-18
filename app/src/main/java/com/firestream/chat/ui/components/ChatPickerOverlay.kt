package com.firestream.chat.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.User

/** The panel's two strings: its bar title, and what it says with no chats to show. */
@Immutable
internal data class ChatPickerLabels(val title: String, val emptyLabel: String)

/**
 * [ChatPickerPanel] mounted over whatever screen the user is already on, for the
 * hosts that already hold the thing being sent — a message to forward, a list to
 * share — and so need no destination of their own.
 *
 * It owns everything about *being* an overlay, so a host is left with only its
 * own two pieces: what the [preview] looks like and what sending means. That
 * includes the search query and the ticked chats, which are ephemeral by
 * definition: a picker that reopened still holding last time's selection would
 * be a way to send a message somewhere by accident.
 *
 * ### Why [target] is nullable instead of the host writing `if (open) { … }`
 *
 * The panel slides out, and a slide-out needs something to slide. Under an `if`,
 * the content would vanish on the frame the host cleared its target and the exit
 * animation would push an empty box across the screen. So the overlay latches
 * the last target it was opened with and keeps drawing it until the transition
 * is over — and hands that same value back to [onSend], so the host never has to
 * keep a second copy of "what am I sending" in sync with this one.
 *
 * ### No progress state, deliberately
 *
 * [ChatPickerState.isSending] stays false here, unlike on the share intent's
 * screen: a forward and a list share are queued rows, so the host closes the
 * panel on the tap rather than awaiting anything, and a spinner would be shown
 * only for the length of the slide-out. What that screen uses `isSending` for
 * as well — refusing a second tap — this owns instead, with `sent` below.
 */
@Composable
internal fun <T : Any> ChatPickerOverlay(
    target: T?,
    chats: List<Chat>,
    currentUserId: String,
    participants: Map<String, User>,
    labels: ChatPickerLabels,
    onDismiss: () -> Unit,
    onSend: (target: T, chats: List<Chat>) -> Unit,
    preview: @Composable BoxScope.(T) -> Unit,
) {
    var latchedTarget by remember { mutableStateOf<T?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedChatIds by rememberSaveable(stateSaver = SelectionSaver) {
        mutableStateOf(emptySet<String>())
    }
    // One send per opening. The panel is still on screen, and its button still
    // live, for the length of the slide-out — without this a second tap forwards
    // the same message twice.
    var sent by remember { mutableStateOf(false) }

    LaunchedEffect(target) {
        if (target != null) {
            latchedTarget = target
            searchQuery = ""
            selectedChatIds = emptySet()
            sent = false
        }
    }

    BackHandler(enabled = target != null) { onDismiss() }

    AnimatedVisibility(
        visible = target != null,
        // The motion a pushed screen arrives with, because that is what the
        // share intent's panel is — same curve, same duration, no added fade.
        enter = screenSlideIn(),
        exit = screenSlideOut(),
    ) {
        val shownTarget = latchedTarget ?: return@AnimatedVisibility
        Box(modifier = Modifier.fillMaxSize()) {
            ChatPickerPanel(
                state = ChatPickerState(
                    title = labels.title,
                    chats = chats,
                    currentUserId = currentUserId,
                    participants = participants,
                    selectedChatIds = selectedChatIds,
                    searchQuery = searchQuery,
                    emptyLabel = labels.emptyLabel,
                ),
                callbacks = ChatPickerCallbacks(
                    onBack = onDismiss,
                    onSearchQueryChange = { searchQuery = it },
                    onToggleChat = { chatId ->
                        selectedChatIds = if (chatId in selectedChatIds) {
                            selectedChatIds - chatId
                        } else {
                            selectedChatIds + chatId
                        }
                    },
                    // Picked from the whole list, not the rows the search happens
                    // to be showing: a chat ticked before the user typed is still
                    // ticked.
                    onSend = {
                        if (!sent) {
                            sent = true
                            onSend(shownTarget, chats.filter { it.id in selectedChatIds })
                        }
                    },
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                preview(shownTarget)
            }
        }
    }
}

/** Survives rotation mid-pick; a `Set<String>` is not Bundle-saveable. */
private val SelectionSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)
