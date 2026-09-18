package com.firestream.chat.ui.components

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.User

// region: AGENT-NOTE
// "Which chat is this, and who is a send to it addressed to" — answered once,
// for every surface that asks. The chat pickers are the main callers;
// ChatListScreen asks sendRecipientId for the chat route it navigates to.
// Deliberately free of Compose, so a ViewModel can call them
// (ui/share/SharePickerViewModel does) and so they can be unit-tested without
// Robolectric — see ui/components/ChatPickerTargetsTest.
//
// Don't put here: anything that draws. The panel and its overlay host are
// ChatPickerPanel.kt / ChatPickerOverlay.kt — see the "One chat picker, three
// hosts" pattern in docs/PATTERNS.md, and add a caller rather than a fourth copy.
// endregion

/** What a chat with no resolvable name is called, matching `ChatListItem`. */
private const val UNNAMED_CHAT = "Chat"

/** The first participant who isn't [currentUserId]; empty when there is none. */
internal fun Chat.otherParticipantId(currentUserId: String): String =
    participants.firstOrNull { it != currentUserId } ?: ""

/**
 * The chat's name as a picker row shows it: the chat's own name, else — for a
 * 1:1 — the other participant's profile name, else a neutral fallback.
 *
 * Never a raw uid: a row reading `8f3c…` tells the user nothing about which
 * conversation they are about to send to.
 */
internal fun Chat.pickerDisplayName(
    currentUserId: String,
    participants: Map<String, User>,
): String = pickerDisplayName(participants[otherParticipantId(currentUserId)])

/** The same rule, for a caller that has already resolved the 1:1 partner. */
internal fun Chat.pickerDisplayName(profile: User?): String =
    name?.takeIf { it.isNotBlank() }
        ?: profile?.displayName?.takeIf { it.isNotBlank() }
        ?: UNNAMED_CHAT

/**
 * The recipient a send to this chat is addressed to — the other participant of a
 * 1:1, and **empty for a group or broadcast**.
 *
 * Signal sessions are 1:1. A group has to go through the plaintext branch in
 * `MessageRepositoryImpl` so every participant can read it; naming one arbitrary
 * member as the recipient would encrypt a group message to that member alone.
 * That was a live bug in the forward dialog the chat picker replaced.
 */
internal fun Chat.sendRecipientId(currentUserId: String): String =
    if (type == ChatType.INDIVIDUAL) otherParticipantId(currentUserId) else ""

/** The rows matching [query] by display name; the whole list when it is blank. */
internal fun List<Chat>.filterChatsByName(
    query: String,
    currentUserId: String,
    participants: Map<String, User>,
): List<Chat> {
    if (query.isBlank()) return this
    return filter { it.pickerDisplayName(currentUserId, participants).contains(query, ignoreCase = true) }
}

/**
 * Where a send went, for the confirmation afterwards: the chat's name when there
 * is one of them, a count when there are several.
 */
internal fun List<Chat>.destinationLabel(
    currentUserId: String,
    participants: Map<String, User>,
): String = if (size == 1) {
    this[0].pickerDisplayName(currentUserId, participants)
} else {
    "$size chats"
}
