package com.firestream.chat.ui.chat

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.MessageRepository

internal class ChatMessageLoader(
    private val chatId: String,
    private val listRepository: ListRepository,
    private val linkPreviewSource: LinkPreviewSource,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val context: Context,
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope
) {

    private var readReceiptJob: Job? = null
    private var screenVisible = false
    private val observedListIds = mutableSetOf<String>()
    private val processedUnshareIds = mutableSetOf<String>()

    fun start() {
        loadMessages()
        observeTyping()
    }

    fun setScreenVisible(visible: Boolean) {
        val wasVisible = screenVisible
        screenVisible = visible
        if (visible && !wasVisible) {
            scope.launch { chatRepository.resetUnreadCount(chatId) }
            val messages = _uiState.value.messages
            if (messages.isNotEmpty()) {
                markIncomingMessagesAsRead(messages)
            }
        } else {
            readReceiptJob?.cancel()
        }
    }

    private fun loadMessages() {
        scope.launch {
            messageRepository.getMessages(chatId)
                .catch { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
                .collectLatest { messages ->
                    _uiState.update {
                        it.copy(
                            messages = messages,
                            isLoading = false,
                            pinnedMessages = messages.filter { msg -> msg.isPinned }
                        )
                    }
                    markIncomingMessagesAsRead(messages)
                    fetchLinkPreviewsFor(messages)
                    observeListMessages(messages)
                }
        }
    }

    private fun markIncomingMessagesAsRead(messages: List<Message>) {
        if (!screenVisible) return
        val currentUserId = _uiState.value.currentUserId
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
        if (!_uiState.value.readReceiptsAllowed) return

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
            if (msg.type.name == "TEXT") {
                val url = linkPreviewSource.extractUrl(msg.content) ?: return@forEach
                if (_uiState.value.linkPreviews.containsKey(url)) return@forEach
                scope.launch {
                    val preview = linkPreviewSource.fetchPreview(url) ?: return@launch
                    _uiState.update { it.copy(linkPreviews = it.linkPreviews + (url to preview)) }
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
                        state.copy(typingUserIds = typingIds.filter { it != state.currentUserId })
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
                    val cached = _uiState.value.listDataCache[listId]
                    if (cached != null && chatId in cached.sharedChatIds) {
                        val updated = cached.copy(sharedChatIds = cached.sharedChatIds - chatId)
                        _uiState.update { it.copy(listDataCache = it.listDataCache + (listId to updated)) }
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
                            _uiState.update { it.copy(listDataCache = it.listDataCache + (listId to listData)) }
                        }
                }
            }
        }
    }
}
