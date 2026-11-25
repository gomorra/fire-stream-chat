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
    // How the alarm rings on completion, and with which sound — the sender's choice,
    // synced to every participant. Always resolved: the wire format's older
    // `timerSilent` boolean is folded in at the data boundary by
    // `resolveTimerAlarmStyle`, so nothing above that layer sees a legacy timer.
    val timerAlarmStyle: TimerAlarmStyle = TimerAlarmStyle.DEFAULT,
    val timerAlarmSound: TimerAlarmSound = TimerAlarmSound.DEFAULT,
    // A DOCUMENT's identity as the sender picked it — display name, byte size and
    // mime type. Null for every other type and for documents sent before these
    // fields existed (whose `content` may still hold a caption, never the name).
    val fileName: String? = null,
    val fileSize: Long? = null,
    val mimeType: String? = null,
    // A CALL that was started as a video call. False for a voice call, for every
    // other type, and for call messages written before the field existed.
    val isVideoCall: Boolean = false,
    // A STICKER names its file by the SHA-256 of the bytes, which is also the
    // file's name on every device. Both come from the sender and are untrusted
    // on receive. The pack id is null when the sender did not share a pack.
    val stickerId: String? = null,
    val stickerPackId: String? = null,
    // Local decrypted copy of an encrypted video thumbnail (device-only).
    val localThumbUri: String? = null,
    // Chunked AES-256-GCM descriptors (Base64 key, base IV, ciphertext SHA-256)
    // for E2EE 1:1 media blobs (.enc) and encrypted video thumbnails.
    val mediaKey: String? = null,
    val mediaIv: String? = null,
    val mediaSha256: String? = null,
    val thumbKey: String? = null,
    val thumbIv: String? = null,
    val thumbSha256: String? = null,
) {
    /** True when [mediaUrl] points to an AES-256-GCM encrypted `.enc` blob. */
    val isEncryptedMedia: Boolean
        get() = mediaKey != null

    /**
     * Media source safe to hand to Coil / ExoPlayer / MediaPlayer.
     * Never returns an encrypted `.enc` remote URL; encrypted media renders
     * exclusively from its decrypted [localUri].
     */
    val displayMediaSource: String?
        get() = if (isEncryptedMedia) localUri else (localUri ?: mediaUrl)

    /**
     * Remote fallback URL safe for UI image viewers (`FullscreenMediaItem.imageUrl`,
     * `SharedMediaTile.mediaUrl`). `null` for encrypted media so decoders never
     * fetch raw `.enc` ciphertext directly.
     */
    val displayRemoteMediaUrl: String?
        get() = if (isEncryptedMedia) null else mediaUrl

    /**
     * Remote thumbnail fallback URL safe for UI image viewers. `null` for
     * encrypted thumbnails (`thumbKey != null`).
     */
    val displayRemoteThumbUrl: String?
        get() = if (thumbKey != null) null else mediaThumbnailUrl

    /**
     * Thumbnail source safe to hand to Coil.
     * Returns [localThumbUri] for encrypted thumbnails (`thumbKey != null`) and
     * never falls back to an encrypted `.enc` [mediaThumbnailUrl].
     */
    val displayThumbSource: String?
        get() = if (thumbKey != null) localThumbUri else (localThumbUri ?: mediaThumbnailUrl)
}
