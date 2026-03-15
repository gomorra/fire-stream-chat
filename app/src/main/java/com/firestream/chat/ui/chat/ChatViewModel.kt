package com.firestream.chat.ui.chat

import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.domain.usecase.chat.CheckGroupPermissionUseCase
import com.firestream.chat.domain.usecase.chat.GetChatsUseCase
import com.firestream.chat.domain.usecase.message.AddReactionUseCase
import com.firestream.chat.domain.usecase.message.DeleteMessageUseCase
import com.firestream.chat.domain.usecase.message.EditMessageUseCase
import com.firestream.chat.domain.usecase.message.ForwardMessageUseCase
import com.firestream.chat.domain.usecase.message.GetMessagesUseCase
import com.firestream.chat.domain.usecase.message.RemoveReactionUseCase
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase
import com.firestream.chat.domain.usecase.message.SendMediaMessageUseCase
import com.firestream.chat.domain.usecase.message.SendMessageUseCase
import com.firestream.chat.domain.usecase.message.SendVoiceMessageUseCase
import com.firestream.chat.domain.usecase.message.SendPollUseCase
import com.firestream.chat.domain.usecase.message.VotePollUseCase
import com.firestream.chat.domain.usecase.message.ClosePollUseCase
import com.firestream.chat.domain.usecase.message.ParseMentionsUseCase
import com.firestream.chat.domain.usecase.message.SendBroadcastMessageUseCase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import com.firestream.chat.domain.usecase.message.StarMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentUserId: String = "",
    val isSending: Boolean = false,
    val typingUserIds: List<String> = emptyList(),
    val editingMessage: Message? = null,
    // Phase 1 state
    val replyToMessage: Message? = null,
    val linkPreviews: Map<String, LinkPreview> = emptyMap(),
    // Forward picker
    val availableChats: List<Chat> = emptyList(),
    // In-chat search
    val searchQuery: String = "",
    val searchResults: List<Message> = emptyList(),
    val isSearchActive: Boolean = false,
    // Read receipts — true only when BOTH users have read receipts enabled
    val readReceiptsAllowed: Boolean = true,
    // Phase 5: group management
    val isGroupChat: Boolean = false,
    val chatName: String? = null,
    // Phase 5.2: group permissions
    val canSendMessages: Boolean = true,
    val isAnnouncementMode: Boolean = false,
    // Phase 5.4: mentions — non-empty list means picker is visible
    val mentionCandidates: List<User> = emptyList(),
    val participantNameMap: Map<String, String> = emptyMap(),
    // Phase 5.5: broadcast
    val isBroadcast: Boolean = false,
    val broadcastRecipientIds: List<String> = emptyList(),
    // Emoji recents (from DataStore)
    val recentEmojis: List<String> = emptyList(),
    // Recipient profile (individual chats only)
    val recipientAvatarUrl: String? = null,
    val isRecipientOnline: Boolean = false,
    val chatAvatarUrl: String? = null
) {
    val broadcastRecipientCount: Int get() = broadcastRecipientIds.size
    val avatarUrl: String? get() = recipientAvatarUrl ?: chatAvatarUrl
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getMessagesUseCase: GetMessagesUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val deleteMessageUseCase: DeleteMessageUseCase,
    private val editMessageUseCase: EditMessageUseCase,
    private val sendMediaMessageUseCase: SendMediaMessageUseCase,
    private val addReactionUseCase: AddReactionUseCase,
    private val removeReactionUseCase: RemoveReactionUseCase,
    private val forwardMessageUseCase: ForwardMessageUseCase,
    private val sendVoiceMessageUseCase: SendVoiceMessageUseCase,
    private val starMessageUseCase: StarMessageUseCase,
    private val sendPollUseCase: SendPollUseCase,
    private val votePollUseCase: VotePollUseCase,
    private val closePollUseCase: ClosePollUseCase,
    private val parseMentionsUseCase: ParseMentionsUseCase,
    private val sendBroadcastMessageUseCase: SendBroadcastMessageUseCase,
    private val checkGroupPermissionUseCase: CheckGroupPermissionUseCase,
    private val getChatsUseCase: GetChatsUseCase,
    private val searchMessagesUseCase: SearchMessagesUseCase,
    private val linkPreviewSource: LinkPreviewSource,
    private val authRepository: AuthRepository,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val preferencesDataStore: PreferencesDataStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val chatId: String = checkNotNull(savedStateHandle["chatId"])
    val recipientId: String = checkNotNull(savedStateHandle["recipientId"])

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var typingDebounceJob: Job? = null
    private var searchJob: Job? = null
    private var screenVisible = false
    private var allGroupParticipants: List<User> = emptyList()
    // Cached inverse of participantNameMap (displayName -> userId) for mention extraction on send
    private var displayNameToUserId: Map<String, String> = emptyMap()

    init {
        _uiState.value = _uiState.value.copy(currentUserId = authRepository.currentUserId ?: "")
        loadMessages()
        observeTyping()
        loadAvailableChats()
        observeReadReceiptsAllowed()
        loadChatInfo()
        observeRecentEmojis()
        if (recipientId.isNotBlank()) observeRecipient()
    }

    private fun loadChatInfo() {
        viewModelScope.launch {
            chatRepository.getChatById(chatId)
                .onSuccess { chat ->
                    val uid = _uiState.value.currentUserId
                    val isGroup = chat.type == ChatType.GROUP
                    val isBroadcast = chat.type == ChatType.BROADCAST
                    val broadcastRecipientIds = if (isBroadcast) chat.participants.filter { it != uid } else emptyList()
                    _uiState.value = _uiState.value.copy(
                        isGroupChat = isGroup,
                        // Only overwrite chatName from the chat document for named chats
                        // (groups/broadcasts). For individual chats chat.name is null and
                        // overwriting here would race with observeRecipient() which sets the
                        // recipient's displayName as chatName from a faster snapshot listener.
                        chatName = if (isGroup || isBroadcast) chat.name else _uiState.value.chatName,
                        chatAvatarUrl = chat.avatarUrl,
                        canSendMessages = if (isGroup) checkGroupPermissionUseCase.canSendMessages(chat, uid) else true,
                        isAnnouncementMode = isGroup && chat.permissions.isAnnouncementMode,
                        isBroadcast = isBroadcast,
                        broadcastRecipientIds = broadcastRecipientIds
                    )
                    if (isGroup) loadGroupParticipants(chat.participants)
                }
        }
    }

    private fun loadGroupParticipants(participantIds: List<String>) {
        viewModelScope.launch {
            val users = participantIds
                .map { userId -> async { userRepository.getUserById(userId).getOrNull() } }
                .awaitAll()
                .filterNotNull()
            val nameMap = users.associate { it.uid to it.displayName }
            allGroupParticipants = users
            displayNameToUserId = nameMap.entries.associate { it.value to it.key }
            _uiState.value = _uiState.value.copy(participantNameMap = nameMap)
        }
    }

    private fun observeRecipient() {
        viewModelScope.launch {
            userRepository.observeUser(recipientId)
                .catch { /* non-fatal */ }
                .collect { user ->
                    _uiState.value = _uiState.value.copy(
                        chatName = user.displayName.takeIf { it.isNotBlank() } ?: _uiState.value.chatName,
                        recipientAvatarUrl = user.avatarUrl,
                        isRecipientOnline = user.isOnline
                    )
                    // Reuse this single listener for read receipts instead of a duplicate observeUser call
                    updateReadReceiptsAllowed(recipientEnabled = user.readReceiptsEnabled)
                }
        }
    }

    /**
     * Observe both the local user's read receipts preference AND the recipient's
     * Firestore setting. Read receipts are only allowed when BOTH are enabled.
     * The recipient's setting is handled by observeRecipient() to avoid a duplicate listener.
     */
    private fun observeReadReceiptsAllowed() {
        // Local user's setting
        viewModelScope.launch {
            preferencesDataStore.readReceiptsFlow.collect { localEnabled ->
                updateReadReceiptsAllowed(localEnabled = localEnabled)
            }
        }
        // Note: recipient's readReceiptsEnabled is handled inside observeRecipient() to avoid
        // opening a second Firestore listener on the same user document.
    }

    private var localReadReceipts: Boolean = true
    private var recipientReadReceipts: Boolean = true

    private fun updateReadReceiptsAllowed(
        localEnabled: Boolean = localReadReceipts,
        recipientEnabled: Boolean = recipientReadReceipts
    ) {
        localReadReceipts = localEnabled
        recipientReadReceipts = recipientEnabled
        val allowed = localEnabled && recipientEnabled
        _uiState.value = _uiState.value.copy(readReceiptsAllowed = allowed)
    }

    fun setScreenVisible(visible: Boolean) {
        val wasVisible = screenVisible
        screenVisible = visible
        if (visible && !wasVisible) {
            viewModelScope.launch { chatRepository.resetUnreadCount(chatId) }
            // When screen becomes visible, check for unread messages
            val messages = _uiState.value.messages
            if (messages.isNotEmpty()) {
                markIncomingMessagesAsRead(messages)
            }
        } else {
            // Cancel pending read receipt when leaving the screen
            readReceiptJob?.cancel()
        }
    }

    private fun loadMessages() {
        viewModelScope.launch {
            getMessagesUseCase(chatId)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
                .collectLatest { messages ->
                    _uiState.value = _uiState.value.copy(
                        messages = messages,
                        isLoading = false
                    )
                    // Mark incoming messages as read
                    markIncomingMessagesAsRead(messages)
                    // Fetch link previews for text messages with URLs
                    fetchLinkPreviewsFor(messages)
                }
        }
    }

    private var readReceiptJob: Job? = null

    private fun markIncomingMessagesAsRead(messages: List<Message>) {
        if (!screenVisible) return
        val currentUserId = _uiState.value.currentUserId
        if (currentUserId.isEmpty()) return

        // Step 1: Any SENT messages need to be marked DELIVERED first
        val needsDelivery = messages
            .filter { it.senderId != currentUserId && it.status == MessageStatus.SENT }
            .map { it.id }
        if (needsDelivery.isNotEmpty()) {
            viewModelScope.launch {
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
        readReceiptJob = viewModelScope.launch {
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
                viewModelScope.launch {
                    val preview = linkPreviewSource.fetchPreview(url) ?: return@launch
                    _uiState.value = _uiState.value.copy(
                        linkPreviews = _uiState.value.linkPreviews + (url to preview)
                    )
                }
            }
        }
    }

    private fun observeTyping() {
        viewModelScope.launch {
            chatRepository.observeTyping(chatId)
                .catch { /* ignore typing errors */ }
                .collect { typingIds ->
                    val othersTyping = typingIds.filter { it != _uiState.value.currentUserId }
                    _uiState.value = _uiState.value.copy(typingUserIds = othersTyping)
                }
        }
    }

    fun onTyping(text: String) {
        if (text.isNotBlank()) {
            viewModelScope.launch { chatRepository.setTyping(chatId, true) }
            typingDebounceJob?.cancel()
            typingDebounceJob = viewModelScope.launch {
                delay(4_000)
                chatRepository.setTyping(chatId, false)
            }
        } else {
            typingDebounceJob?.cancel()
            viewModelScope.launch { chatRepository.setTyping(chatId, false) }
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return
        typingDebounceJob?.cancel()
        val state = _uiState.value
        viewModelScope.launch {
            chatRepository.setTyping(chatId, false)
            _uiState.value = _uiState.value.copy(isSending = true, replyToMessage = null, mentionCandidates = emptyList())
            if (state.isBroadcast) {
                sendBroadcastMessageUseCase(chatId, content, state.broadcastRecipientIds)
                    .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message, isSending = false) }
                    .onSuccess { _uiState.value = _uiState.value.copy(isSending = false) }
            } else {
                val replyToId = state.replyToMessage?.id
                val mentions = if (state.isGroupChat) parseMentionsUseCase(content, displayNameToUserId) else emptyList()
                sendMessageUseCase(chatId, content, recipientId, replyToId, mentions)
                    .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message, isSending = false) }
                    .onSuccess { _uiState.value = _uiState.value.copy(isSending = false) }
            }
        }
    }

    fun onTypingWithMentions(text: String) {
        onTyping(text)
        if (!_uiState.value.isGroupChat) return
        val query = extractMentionQuery(text)
        if (query != null) {
            val candidates = allGroupParticipants.filter {
                query.isEmpty() || it.displayName.contains(query, ignoreCase = true)
            }.take(8)
            _uiState.value = _uiState.value.copy(mentionCandidates = candidates)
        } else {
            _uiState.value = _uiState.value.copy(mentionCandidates = emptyList())
        }
    }

    /** Returns the new text with the selected mention inserted; caller must update their text field. */
    fun selectMention(user: User, currentText: String): String {
        val atIndex = currentText.lastIndexOf('@')
        val newText = if (atIndex >= 0) {
            currentText.substring(0, atIndex + 1) + user.displayName + " "
        } else {
            currentText
        }
        _uiState.value = _uiState.value.copy(mentionCandidates = emptyList())
        return newText
    }

    private fun extractMentionQuery(text: String): String? {
        val atIndex = text.lastIndexOf('@')
        if (atIndex < 0) return null
        val afterAt = text.substring(atIndex + 1)
        return if (afterAt.contains(' ')) null else afterAt
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            deleteMessageUseCase(chatId, messageId)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun startEdit(message: Message) {
        _uiState.value = _uiState.value.copy(editingMessage = message)
    }

    fun cancelEdit() {
        _uiState.value = _uiState.value.copy(editingMessage = null)
    }

    fun confirmEdit(newContent: String) {
        val msg = _uiState.value.editingMessage ?: return
        if (newContent.isBlank()) return
        _uiState.value = _uiState.value.copy(editingMessage = null)
        viewModelScope.launch {
            editMessageUseCase(chatId, msg.id, newContent)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun sendMediaMessage(uri: Uri, mimeType: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true)
            sendMediaMessageUseCase(chatId, uri, mimeType, recipientId)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message, isSending = false) }
                .onSuccess { _uiState.value = _uiState.value.copy(isSending = false) }
        }
    }

    // Phase 1: reply-to
    fun setReplyTo(message: Message) {
        _uiState.value = _uiState.value.copy(replyToMessage = message)
    }

    fun clearReplyTo() {
        _uiState.value = _uiState.value.copy(replyToMessage = null)
    }

    // Phase 1: reactions
    fun toggleReaction(messageId: String, emoji: String) {
        val currentUserId = _uiState.value.currentUserId
        val message = _uiState.value.messages.find { it.id == messageId } ?: return
        viewModelScope.launch {
            if (message.reactions[currentUserId] == emoji) {
                removeReactionUseCase(chatId, messageId, currentUserId)
            } else {
                addReactionUseCase(chatId, messageId, currentUserId, emoji)
            }
        }
    }

    // Phase 1: forwarding
    fun forwardMessage(message: Message, targetChatId: String, targetRecipientId: String) {
        viewModelScope.launch {
            forwardMessageUseCase(message, targetChatId, targetRecipientId)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    // Phase 1: voice messages
    fun sendVoiceMessage(uri: Uri, durationSeconds: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true)
            sendVoiceMessageUseCase(chatId, uri, recipientId, durationSeconds)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message, isSending = false) }
                .onSuccess { _uiState.value = _uiState.value.copy(isSending = false) }
        }
    }

    // Phase 2: star/unstar a message
    fun toggleStar(message: Message) {
        viewModelScope.launch {
            starMessageUseCase(message.id, !message.isStarred)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    // Load available chats for the forward picker
    private fun loadAvailableChats() {
        viewModelScope.launch {
            try {
                val chats = getChatsUseCase().first()
                _uiState.value = _uiState.value.copy(availableChats = chats)
            } catch (_: Exception) {
                // Non-critical — forward picker will show empty
            }
        }
    }

    // In-chat search with debounce
    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList())
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            try {
                val results = searchMessagesUseCase(query, chatId)
                _uiState.value = _uiState.value.copy(searchResults = results)
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(searchResults = emptyList())
            }
        }
    }

    fun toggleSearch() {
        val newActive = !_uiState.value.isSearchActive
        _uiState.value = _uiState.value.copy(
            isSearchActive = newActive,
            searchQuery = if (newActive) _uiState.value.searchQuery else "",
            searchResults = if (newActive) _uiState.value.searchResults else emptyList()
        )
        if (!newActive) searchJob?.cancel()
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isSearchActive = false,
            searchQuery = "",
            searchResults = emptyList()
        )
    }

    // Phase 5.3: polls
    fun sendPoll(question: String, options: List<String>, isMultipleChoice: Boolean, isAnonymous: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true)
            sendPollUseCase(chatId, question, options, isMultipleChoice, isAnonymous)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message, isSending = false) }
                .onSuccess { _uiState.value = _uiState.value.copy(isSending = false) }
        }
    }

    fun votePoll(messageId: String, optionIds: List<String>) {
        viewModelScope.launch {
            votePollUseCase(chatId, messageId, optionIds)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun closePoll(messageId: String) {
        viewModelScope.launch {
            closePollUseCase(chatId, messageId)
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // Emoji recents
    private fun observeRecentEmojis() {
        viewModelScope.launch {
            preferencesDataStore.recentEmojisFlow
                .distinctUntilChanged()
                .collect { recents ->
                    _uiState.value = _uiState.value.copy(recentEmojis = recents)
                }
        }
    }

    fun addRecentEmoji(emoji: String) {
        viewModelScope.launch {
            preferencesDataStore.addRecentEmoji(emoji)
        }
    }

    override fun onCleared() {
        super.onCleared()
        typingDebounceJob?.cancel()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            chatRepository.setTyping(chatId, false)
        }
    }
}
