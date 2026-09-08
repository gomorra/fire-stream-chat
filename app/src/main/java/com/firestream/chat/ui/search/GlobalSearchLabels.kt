package com.firestream.chat.ui.search

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.model.Message

/** What a chat with no resolvable name is called, matching `ChatListItem`. */
private const val UNNAMED_CHAT = "Chat"

/**
 * The participant of a 1:1 chat who isn't [currentUserId]; empty for groups,
 * broadcasts, and a chat that isn't there.
 *
 * The one copy this package uses — for resolving a title *and* for building
 * the chat route a result tap navigates to, which is the same question twice.
 */
internal fun Chat?.otherParticipant(currentUserId: String): String {
    if (this == null || type != ChatType.INDIVIDUAL) return ""
    return participants.firstOrNull { it != currentUserId } ?: ""
}

/**
 * The chat's title as the chat list shows it: for a 1:1 the other participant's
 * contact name, otherwise the chat's own name.
 *
 * Deliberately mirrors `ChatListItem`'s resolution rather than reading
 * `chat.name` alone — a 1:1 row's `name` is often null, and a global result
 * that says "Chat" for a conversation the list calls "Alice" is worse than no
 * label at all.
 */
internal fun Chat?.displayTitle(
    contacts: Map<String, Contact>,
    currentUserId: String,
): String {
    if (this == null) return UNNAMED_CHAT
    return otherParticipant(currentUserId)
        .takeIf { it.isNotEmpty() }
        ?.let { contacts[it]?.displayName?.takeIf { n -> n.isNotBlank() } }
        ?: name?.takeIf { it.isNotBlank() }
        ?: UNNAMED_CHAT
}

/**
 * The line above a global result: `Alice · Weekend Trip`.
 *
 * A global hit is useless without saying *where* it came from, but repeating
 * the name on both sides of the separator ("Alice · Alice") is noise — in a 1:1
 * the incoming sender *is* the chat title, so the label collapses to the title
 * alone. An unresolvable sender collapses the same way rather than rendering a
 * raw uid.
 */
internal fun globalResultLabel(
    message: Message,
    chat: Chat?,
    contacts: Map<String, Contact>,
    currentUserId: String,
): String {
    val chatTitle = chat.displayTitle(contacts, currentUserId)
    val senderName = when {
        message.senderId == currentUserId -> "You"
        else -> contacts[message.senderId]?.displayName?.takeIf { it.isNotBlank() }
    }
    return when (senderName) {
        null, chatTitle -> chatTitle
        else -> "$senderName · $chatTitle"
    }
}
