package com.firestream.chat.ui.chat

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.ReminderRepository

/**
 * A reaction that another user just added to (or changed on) one of the current
 * user's own messages, surfaced so the chat screen can highlight the bubble or
 * offer a jump-to-reaction affordance. [emoji] is the newly added/changed emoji.
 */
internal data class ReactionAlert(val messageId: String, val emoji: String)

/**
 * Diffs two message lists and returns the reactions that are *new to me*: a
 * reaction added or changed by someone other than [currentUserId] on a message
 * the current user authored. Mirrors the Cloud Function's added-or-changed
 * semantics (`diffAddedReactions`) — removals and the user's own reactions are
 * ignored. Pure and side-effect free so it can be unit-tested without Compose.
 */
internal fun detectNewOwnReactions(
    previous: List<Message>,
    current: List<Message>,
    currentUserId: String
): List<ReactionAlert> {
    if (currentUserId.isBlank()) return emptyList()
    val previousById = previous.associateBy { it.id }
    val alerts = mutableListOf<ReactionAlert>()
    for (message in current) {
        if (message.senderId != currentUserId) continue
        val before = previousById[message.id]?.reactions ?: emptyMap()
        var newest: String? = null
        for ((reactorId, emoji) in message.reactions) {
            if (reactorId == currentUserId) continue
            if (before[reactorId] != emoji) newest = emoji // added or changed
        }
        if (newest != null) alerts += ReactionAlert(message.id, newest)
    }
    return alerts
}

internal class ChatMessageLoader(
    private val chatId: String,
    private val listRepository: ListRepository,
    private val linkPreviewSource: LinkPreviewSource,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val reminderRepository: ReminderRepository,
    private val context: Context,
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope
) {

    private var readReceiptJob: Job? = null
    private var pendingUnreadResetJob: Job? = null
    private var screenVisible = false
    private var atBottom: Boolean = true
    private var lastResetIncomingId: String? = null
    private val observedListIds = mutableSetOf<String>()
    private val processedUnshareIds = mutableSetOf<String>()

    // Baseline for reaction-diff detection. `null` until the first emission so we
    // don't fire alerts for reactions that already existed when the chat opened.
    private var previousMessages: List<Message>? = null

    // Side-channel: reactions another user just added to one of my messages, so
    // ChatScreen can flash the bubble (if visible) or show a jump-to-reaction FAB.
    private val _reactionAlerts = MutableSharedFlow<ReactionAlert>(extraBufferCapacity = 16)
    val reactionAlerts: SharedFlow<ReactionAlert> = _reactionAlerts.asSharedFlow()

    fun start() {
        loadMessages()
        observeTyping()
    }

    fun setScreenVisible(visible: Boolean) {
        val wasVisible = screenVisible
        screenVisible = visible
        if (visible && !wasVisible) {
            maybeResetUnread(force = true)
            val messages = _uiState.value.messages.messages
            if (messages.isNotEmpty()) {
                markIncomingMessagesAsRead(messages)
            }
        } else if (!visible) {
            readReceiptJob?.cancel()
            pendingUnreadResetJob?.cancel()
        }
    }

    fun setAtBottom(value: Boolean) {
        val wasAtBottom = atBottom
        atBottom = value
        if (value && !wasAtBottom && screenVisible) {
            maybeResetUnread(force = true)
        }
    }

    // Resets the chat's per-user unread count to 0 when the user is visibly
    // reading new messages (screen visible + scrolled to the bottom). We trigger
    // a second reset ~2s later because the Cloud Function `sendPushNotification`
    // writes `FieldValue.increment(1)` asynchronously — it can land AFTER our
    // immediate reset, leaving a stale badge in the chat list.
    private fun maybeResetUnread(force: Boolean = false) {
        if (!screenVisible || !atBottom) return
        val currentUserId = _uiState.value.session.currentUserId
        val tail = _uiState.value.messages.messages.lastOrNull { it.senderId != currentUserId }?.id
        if (!force && tail == lastResetIncomingId) return
        lastResetIncomingId = tail
        scope.launch { chatRepository.resetUnreadCount(chatId) }
        pendingUnreadResetJob?.cancel()
        pendingUnreadResetJob = scope.launch {
            delay(2000)
            if (screenVisible && atBottom) {
                chatRepository.resetUnreadCount(chatId)
            }
        }
    }

    private fun loadMessages() {
        scope.launch {
            messageRepository.getMessages(chatId)
                .combine(reminderRepository.observePendingIdsForChat(chatId)) { messages, pendingReminderIds ->
                    messages to pendingReminderIds
                }
                .catch { e ->
                    _uiState.update {
                        it.copy(session = it.session.copy(isLoading = false, error = AppError.from(e)))
                    }
                }
                .collectLatest { (messages, pendingReminderIds) ->
                    _uiState.update {
                        it.copy(
                            messages = it.messages.copy(
                                messages = messages,
                                pinnedMessages = messages.filter { msg -> msg.isPinned },
                                pendingReminderIds = pendingReminderIds
                            ),
                            session = it.session.copy(isLoading = false)
                        )
                    }
                    emitReactionAlerts(messages)
                    maybeResetUnread()
                    markIncomingMessagesAsRead(messages)
                    fetchLinkPreviewsFor(messages)
                    observeListMessages(messages)
                }
        }
    }

    // Compares each new message-list emission against the previous one and emits
    // an alert for any reaction another user just added to one of my messages.
    // The first emission only establishes the baseline (no alerts) so pre-existing
    // reactions don't fire on chat open.
    private fun emitReactionAlerts(messages: List<Message>) {
        val previous = previousMessages
        previousMessages = messages
        if (previous == null) return
        val currentUserId = _uiState.value.session.currentUserId
        detectNewOwnReactions(previous, messages, currentUserId).forEach { _reactionAlerts.tryEmit(it) }
    }

    private fun markIncomingMessagesAsRead(messages: List<Message>) {
        if (!screenVisible) return
        val currentUserId = _uiState.value.session.currentUserId
        if (currentUserId.isEmpty()) return

        // Step 1: Any SENT messages need to be marked DELIVERED first
        val needsDelivery = messages
            .filter { it.senderId != currentUserId && it.status == MessageStatus.SENT }
            .map { it.id }
        if (needsDelivery.isNotEmpty()) {
            scope.launch {
                messageRepository.markMessagesAsDelivered(chatId, needsDelivery)
            }
            // Return here — the Firestore update will trigger a new collect emission
            // with DELIVERED status, at which point we'll proceed to mark READ below.
            // This ensures the sender sees ✓✓ before it turns blue.
            return
        }

        // Step 2: Skip READ marking if either user has disabled read receipts
        if (!_uiState.value.session.readReceiptsAllowed) return

        // Step 3: Mark DELIVERED messages as READ after a short delay
        val needsRead = messages
            .filter { it.senderId != currentUserId && it.status == MessageStatus.DELIVERED }
            .map { it.id }
        if (needsRead.isEmpty()) return

        readReceiptJob?.cancel()
        readReceiptJob = scope.launch {
            delay(1500)
            if (screenVisible) {
                messageRepository.markMessagesAsRead(chatId, needsRead)
                NotificationManagerCompat.from(context).cancel(chatId.hashCode())
            }
        }
    }

    private fun fetchLinkPreviewsFor(messages: List<Message>) {
        messages.forEach { msg ->
            if (msg.type == MessageType.TEXT) {
                val url = linkPreviewSource.extractUrl(msg.content) ?: return@forEach
                if (_uiState.value.overlays.linkPreviews.containsKey(url)) return@forEach
                scope.launch {
                    val preview = linkPreviewSource.fetchPreview(url) ?: return@launch
                    _uiState.update {
                        it.copy(overlays = it.overlays.copy(linkPreviews = it.overlays.linkPreviews + (url to preview)))
                    }
                }
            }
        }
    }

    private fun observeTyping() {
        scope.launch {
            chatRepository.observeTyping(chatId)
                .catch { /* ignore typing errors */ }
                .collect { typingIds ->
                    _uiState.update { state ->
                        state.copy(session = state.session.copy(typingUserIds = typingIds.filter { it != state.session.currentUserId }))
                    }
                }
        }
    }

    fun observeListMessages(messages: List<Message>) {
        // Proactively invalidate cache for unshared lists — strip this chatId from
        // sharedChatIds so the bubble becomes non-clickable immediately, without
        // waiting for the Firestore listener to fire. Only process each unshare
        // message once; re-running on every emission is wasteful and idempotent anyway.
        messages.filter { it.type == MessageType.LIST && it.listDiff?.unshared == true && it.listId != null }
            .forEach { msg ->
                val listId = msg.listId ?: return@forEach
                if (processedUnshareIds.add(msg.id)) {
                    val cached = _uiState.value.overlays.listDataCache[listId]
                    if (cached != null && chatId in cached.sharedChatIds) {
                        val updated = cached.copy(sharedChatIds = cached.sharedChatIds - chatId)
                        _uiState.update {
                            it.copy(overlays = it.overlays.copy(listDataCache = it.overlays.listDataCache + (listId to updated)))
                        }
                    }
                }
            }

        val listIds = messages
            .filter { it.type == MessageType.LIST && it.listId != null }
            .mapNotNull { it.listId }
            .distinct()
            .take(10)

        listIds.forEach { listId ->
            if (listId !in observedListIds) {
                observedListIds.add(listId)
                scope.launch {
                    listRepository.observeList(listId)
                        .catch { /* non-fatal */ }
                        .collect { listData ->
                            _uiState.update {
                                it.copy(overlays = it.overlays.copy(listDataCache = it.overlays.listDataCache + (listId to listData)))
                            }
                        }
                }
            }
        }
    }
}
