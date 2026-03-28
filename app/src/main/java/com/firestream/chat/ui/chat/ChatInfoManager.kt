package com.firestream.chat.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.domain.usecase.chat.CheckGroupPermissionUseCase
import com.firestream.chat.domain.usecase.chat.GetChatsUseCase
import com.firestream.chat.domain.usecase.list.CreateListUseCase

internal class ChatInfoManager(
    private val chatId: String,
    private val recipientId: String,
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val preferencesDataStore: PreferencesDataStore,
    private val checkGroupPermissionUseCase: CheckGroupPermissionUseCase,
    private val getChatsUseCase: GetChatsUseCase,
    private val createListUseCase: CreateListUseCase,
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope
) {

    private var allGroupParticipants: List<User> = emptyList()
    private var localReadReceipts: Boolean = true
    private var recipientReadReceipts: Boolean = true

    fun start() {
        loadAvailableChats()
        observeReadReceiptsAllowed()
        loadChatInfo()
        observeRecentEmojis()
        if (recipientId.isNotBlank()) observeRecipient()
    }

    private fun loadChatInfo() {
        scope.launch {
            chatRepository.getChatById(chatId)
                .onSuccess { chat ->
                    val uid = _uiState.value.currentUserId
                    val isGroup = chat.type == ChatType.GROUP
                    val isBroadcast = chat.type == ChatType.BROADCAST
                    val broadcastRecipientIds = if (isBroadcast) chat.participants.filter { it != uid } else emptyList()
                    _uiState.update {
                        it.copy(
                            isGroupChat = isGroup,
                            chatName = if (isGroup || isBroadcast) chat.name else it.chatName,
                            chatAvatarUrl = chat.avatarUrl,
                            canSendMessages = if (isGroup) checkGroupPermissionUseCase.canSendMessages(chat, uid) else true,
                            isAnnouncementMode = isGroup && chat.permissions.isAnnouncementMode,
                            isBroadcast = isBroadcast,
                            broadcastRecipientIds = broadcastRecipientIds
                        )
                    }
                    if (isGroup) loadGroupParticipants(chat.participants)
                }
        }
    }

    private fun loadGroupParticipants(participantIds: List<String>) {
        scope.launch {
            val users = participantIds
                .map { userId -> async { userRepository.getUserById(userId).getOrNull() } }
                .awaitAll()
                .filterNotNull()
            val nameMap = users.associate { it.uid to it.displayName }
            allGroupParticipants = users
            _uiState.update { it.copy(participantNameMap = nameMap) }
        }
    }

    private fun observeRecipient() {
        scope.launch {
            userRepository.observeUser(recipientId)
                .catch { /* non-fatal */ }
                .collect { user ->
                    _uiState.update {
                        it.copy(
                            chatName = user.displayName.takeIf { n -> n.isNotBlank() } ?: it.chatName,
                            recipientAvatarUrl = user.avatarUrl,
                            isRecipientOnline = user.isOnline
                        )
                    }
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
        scope.launch {
            preferencesDataStore.readReceiptsFlow.collect { localEnabled ->
                updateReadReceiptsAllowed(localEnabled = localEnabled)
            }
        }
    }

    private fun updateReadReceiptsAllowed(
        localEnabled: Boolean = localReadReceipts,
        recipientEnabled: Boolean = recipientReadReceipts
    ) {
        localReadReceipts = localEnabled
        recipientReadReceipts = recipientEnabled
        val allowed = localEnabled && recipientEnabled
        _uiState.update { it.copy(readReceiptsAllowed = allowed) }
    }

    private fun loadAvailableChats() {
        scope.launch {
            try {
                val chats = getChatsUseCase().first()
                val participants = chats.resolveChatParticipants(_uiState.value.currentUserId, userRepository)
                _uiState.update { it.copy(availableChats = chats, chatParticipants = participants) }
            } catch (_: Exception) {
                // Non-critical — forward picker will show empty
            }
        }
    }

    private fun observeRecentEmojis() {
        scope.launch {
            preferencesDataStore.recentEmojisFlow
                .distinctUntilChanged()
                .collect { recents ->
                    _uiState.update { it.copy(recentEmojis = recents) }
                }
        }
    }

    fun addRecentEmoji(emoji: String) {
        scope.launch {
            preferencesDataStore.addRecentEmoji(emoji)
        }
    }

    fun updateMentionCandidates(text: String) {
        if (!_uiState.value.isGroupChat) return
        val query = extractMentionQuery(text)
        if (query != null) {
            val candidates = allGroupParticipants.filter {
                query.isEmpty() || it.displayName.contains(query, ignoreCase = true)
            }.take(8)
            _uiState.update { it.copy(mentionCandidates = candidates) }
        } else {
            _uiState.update { it.copy(mentionCandidates = emptyList()) }
        }
    }

    fun selectMention(user: User, currentText: String): String {
        val atIndex = currentText.lastIndexOf('@')
        val newText = if (atIndex >= 0) {
            currentText.substring(0, atIndex + 1) + user.displayName + " "
        } else {
            currentText
        }
        _uiState.update { it.copy(mentionCandidates = emptyList()) }
        return newText
    }

    private fun extractMentionQuery(text: String): String? {
        val atIndex = text.lastIndexOf('@')
        if (atIndex < 0) return null
        val afterAt = text.substring(atIndex + 1)
        return if (afterAt.contains(' ')) null else afterAt
    }

    fun createAndSendList(title: String, type: ListType) {
        scope.launch {
            _uiState.update { it.copy(isSending = true) }
            createListUseCase(title, type, chatId)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
            _uiState.update { it.copy(isSending = false) }
        }
    }
}
