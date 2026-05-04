package com.firestream.chat.domain.model

data class Message(
    val id: String = "",
    val chatId: String = "",
    val senderId: String = "",
    val content: String = "",
    val type: MessageType = MessageType.TEXT,
    val mediaUrl: String? = null,
    val mediaThumbnailUrl: String? = null,
    val localUri: String? = null,
    val mediaWidth: Int? = null,
    val mediaHeight: Int? = null,
    val status: MessageStatus = MessageStatus.SENDING,
    val replyToId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val editedAt: Long? = null,
    // Phase 1: reactions — map of userId → emoji
    val reactions: Map<String, String> = emptyMap(),
    // Phase 1: forwarding
    val isForwarded: Boolean = false,
    // Phase 1: voice messages — duration in seconds
    val duration: Int? = null,
    // Phase 2: starred messages
    val isStarred: Boolean = false,
    // Per-recipient delivery/read tracking for group chats
    val readBy: Map<String, Long> = emptyMap(),
    val deliveredTo: Map<String, Long> = emptyMap(),
    // Phase 5.3: polls
    val pollData: Poll? = null,
    // Phase 5.4: mentions — userIds; "everyone" for @everyone
    val mentions: List<String> = emptyList(),
    val deletedAt: Long? = null,
    // Emoji size overrides — maps character index in content → size multiplier (0.8–2.5)
    val emojiSizes: Map<Int, Float> = emptyMap(),
    // Lists feature
    val listId: String? = null,
    val listDiff: ListDiff? = null,
    // Generic message pinning
    val isPinned: Boolean = false,
    // Location sharing
    val latitude: Double? = null,
    val longitude: Double? = null,
    // Image was sent without compression (Settings → "Send images in full quality").
    // Drives the HD badge on image bubbles; false for any other message type.
    val isHd: Boolean = false,
    // Timer (.timer.set) — set together when the message is a TIMER. Fire time
    // is timerStartedAtMs + timerDurationMs (server-stamped on the sender's
    // write so both devices schedule against the same instant).
    val timerDurationMs: Long? = null,
    val timerStartedAtMs: Long? = null,
    val timerState: TimerState? = null,
    // Frozen remaining ms when state = PAUSED; null while RUNNING or in a terminal state.
    val timerRemainingMs: Long? = null,
    // When true the alarm notification is suppressed on completion (bubble still flips to COMPLETED).
    val timerSilent: Boolean = false,
)
