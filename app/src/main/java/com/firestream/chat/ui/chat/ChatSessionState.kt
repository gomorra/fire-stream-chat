package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.User

internal data class ParticipantAvatar(
    val displayName: String,
    val avatarUrl: String?,
    val localAvatarPath: String?,
)

internal data class SessionState(
    val isLoading: Boolean = true,
    val error: AppError? = null,
    val currentUserId: String = "",
    val typingUserIds: List<String> = emptyList(),
    val availableChats: List<Chat> = emptyList(),
    val chatParticipants: Map<String, User> = emptyMap(),
    val readReceiptsAllowed: Boolean = true,
    val isGroupChat: Boolean = false,
    val chatName: String? = null,
    val participantNameMap: Map<String, String> = emptyMap(),
    val participantAvatars: Map<String, ParticipantAvatar> = emptyMap(),
    val isBroadcast: Boolean = false,
    val broadcastRecipientIds: List<String> = emptyList(),
    val recipientAvatarUrl: String? = null,
    val recipientLocalAvatarPath: String? = null,
    val isRecipientOnline: Boolean = false,
    val chatAvatarUrl: String? = null,
    val chatLocalAvatarPath: String? = null,
    // True when the current user has blocked the 1:1 recipient. Used to replace
    // the composer with an "Unblock to send" banner so the user sees the state
    // before they try to send.
    val isRecipientBlocked: Boolean = false,
    // True while the device has no validated network. Display only — it explains
    // the clock icon on a queued message ("Waiting for network…" in the top bar);
    // nothing in the send path reads it, the outbox job's CONNECTED constraint
    // decides when a send actually runs.
    val isOffline: Boolean = false,
) {
    /**
     * Whether the top bar reads "Online" for a 1:1 recipient.
     *
     * Presence (RTDB) and the typing indicator (the Firestore chat document)
     * travel over two connections that reconnect on their own schedules, so the
     * recipient's typing can land seconds before their presence flag does —
     * dots at the bottom of the chat and no "Online" at the top. Someone typing
     * in this chat is online whatever the presence channel has said so far, so
     * the header follows the typing indicator as well. [typingUserIds] never
     * contains the current user, and a group or broadcast has no single
     * recipient whose status the header could show.
     */
    val recipientAppearsOnline: Boolean
        get() = isRecipientOnline || (!isGroupChat && !isBroadcast && typingUserIds.isNotEmpty())
}

/**
 * The name to show for [senderId] in this chat.
 *
 * [participantAvatars] is the map to read: it is populated for 1:1 chats
 * (from `observeRecipient`) as well as for groups, whereas
 * [participantNameMap] is group-only. The raw id is the last resort so a row
 * never renders blank — it is not something to show on purpose.
 */
internal fun SessionState.senderDisplayName(senderId: String): String =
    if (senderId == currentUserId) {
        "You"
    } else {
        participantAvatars[senderId]?.displayName ?: chatName ?: senderId
    }
