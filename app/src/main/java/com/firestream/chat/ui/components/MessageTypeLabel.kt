package com.firestream.chat.ui.components

import com.firestream.chat.domain.model.MessageType

/**
 * What a message with no text of its own is called, so a row or a preview is
 * not a blank line.
 *
 * One copy, in the package both readers can reach: the search results
 * (`ui/search/SearchResults.kt`) and the forward preview (`ui/chat/
 * ForwardMessagePanel.kt`) label the same messages, and two copies would drift
 * the first time a `MessageType` is added.
 */
internal val MessageType.placeholderLabel: String
    get() = when (this) {
        MessageType.IMAGE -> "Photo"
        MessageType.VIDEO -> "Video"
        MessageType.VOICE -> "Voice message"
        MessageType.DOCUMENT -> "Document"
        MessageType.LOCATION -> "Location"
        MessageType.POLL -> "Poll"
        MessageType.CALL -> "Call"
        MessageType.LIST -> "List"
        MessageType.TIMER -> "Timer"
        MessageType.TEXT -> ""
    }
