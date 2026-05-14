// region: AGENT-NOTE
// Responsibility: Loads and observes chat-level metadata for ChatScreen — chat doc,
//   recipient profile, group permissions, recent emojis, mention candidates.
// Owns: ChatUiState.session.* (chat name/avatar, blocked state, isLoading)
//   — and currently also writes ComposerState.canSendMessages / mentionCandidates
//   and OverlaysState.recentEmojis (Phase 2 will move these out per the manager
//   contract — see docs/PATTERNS.md#chat-manager-slice-ownership).
// Collaborators: ChatViewModel (composition root), ChatRepository, UserRepository,
//   ListRepository, CheckGroupPermissionUseCase, PreferencesDataStore.
// Don't put here: composer state writes, message send/edit, overlay state writes.
//   New session fields are fine; cross-slice writes are not.
// endregion

package com.firestream.chat.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.domain.usecase.chat.CheckGroupPermissionUseCase

internal class ChatInfoManager(
    private val chatId: String,
    private val recipientId: String,
    private val chatRepository: ChatRepository,
    private val listRepository: ListRepository,
    private val userRepository: UserRepository,
    private val preferencesDataStore: PreferencesDataStore,
    private val checkGroupPermissionUseCase: CheckGroupPermissionUseCase,
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope
) {

    private var allGroupParticipants: List<User> = emptyList()
    private var localReadReceipts: Boolean = true
    private var recipientReadReceipts: Boolean = true
    private var recentsInitialized = false

    fun start() {
        loadAvailableChats()
        observeReadReceiptsAllowed()
        loadChatInfo()
        observeRecentEmojis()
        if (recipientId.isNotBlank()) {
            observeRecipient()
            refreshBlockState()
        }
    }

    /**
     * Re-check whether the current user has blocked the 1:1 recipient. Called
     * on [start] and whenever the chat screen resumes — the user may have
     * toggled the block state in the profile screen and navigated back.
     */
    fun refreshBlockState() {
        if (recipientId.isBlank()) return
        scope.launch {
            val blocked = runCatching { userRepository.isUserBlocked(recipientId) }
                .getOrDefault(false)
            _uiState.update { it.copy(session = it.session.copy(isRecipientBlocked = blocked)) }
        }
    }

    private fun loadChatInfo() {
        scope.launch {
            chatRepository.getChatById(chatId)
                .onSuccess { chat ->
                    val uid = _uiState.value.session.currentUserId
                    val isGroup = chat.type == ChatType.GROUP
                    val isBroadcast = chat.type == ChatType.BROADCAST
                    val broadcastRecipientIds = if (isBroadcast) chat.participants.filter { it != uid } else emptyList()
                    _uiState.update {
                        it.copy(
                            composer = it.composer.copy(
                                canSendMessages = if (isGroup) checkGroupPermissionUseCase.canSendMessages(chat, uid) else true,
                                isAnnouncementMode = isGroup && chat.permissions.isAnnouncementMode
                            ),
                            session = it.session.copy(
                                isGroupChat = isGroup,
                                chatName = if (isGroup || isBroadcast) chat.name else it.session.chatName,
                                chatAvatarUrl = chat.avatarUrl,
                                chatLocalAvatarPath = chat.localAvatarPath,
                                isBroadcast = isBroadcast,
                                broadcastRecipientIds = broadcastRecipientIds
                            )
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
            val avatarMap = users.associate {
                it.uid to ParticipantAvatar(it.displayName, it.avatarUrl, it.localAvatarPath)
            }
            allGroupParticipants = users
            _uiState.update {
                it.copy(session = it.session.copy(
                    participantNameMap = nameMap,
                    participantAvatars = it.session.participantAvatars + avatarMap
                ))
            }
        }
    }

    private fun observeRecipient() {
        scope.launch {
            userRepository.observeUser(recipientId)
                .catch { /* non-fatal */ }
                .collect { user ->
                    val avatar = ParticipantAvatar(user.displayName, user.avatarUrl, user.localAvatarPath)
                    _uiState.update {
                        // Reuse the existing map when avatar fields are unchanged, so RTDB
                        // presence ticks (online/lastSeen) don't churn participantAvatars.
                        val avatars = if (it.session.participantAvatars[user.uid] == avatar) {
                            it.session.participantAvatars
                        } else {
                            it.session.participantAvatars + (user.uid to avatar)
                        }
                        it.copy(
                            session = it.session.copy(
                                chatName = user.displayName.takeIf { n -> n.isNotBlank() } ?: it.session.chatName,
                                recipientAvatarUrl = user.avatarUrl,
                                recipientLocalAvatarPath = user.localAvatarPath,
                                isRecipientOnline = user.isOnline,
                                participantAvatars = avatars
                            )
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
        _uiState.update { it.copy(session = it.session.copy(readReceiptsAllowed = allowed)) }
    }

    private fun loadAvailableChats() {
        scope.launch {
            try {
                val chats = chatRepository.getChats().first()
                val participants = chats.resolveChatParticipants(_uiState.value.session.currentUserId, userRepository)
                _uiState.update {
                    it.copy(session = it.session.copy(availableChats = chats, chatParticipants = participants))
                }
            } catch (_: Exception) {
                // Non-critical — forward picker will show empty
            }
        }
    }

    private fun observeRecentEmojis() {
        scope.launch {
            preferencesDataStore.recentEmojisFlow
                .distinctUntilChanged()
                .collectLatest { recents ->
                    if (!recentsInitialized) {
                        recentsInitialized = true
                    } else {
                        delay(3_000L)
                    }
                    _uiState.update { it.copy(overlays = it.overlays.copy(recentEmojis = recents)) }
                }
        }
    }

    fun addRecentEmoji(emoji: String) {
        scope.launch {
            preferencesDataStore.addRecentEmoji(emoji)
        }
    }

    fun updateMentionCandidates(text: String) {
        if (!_uiState.value.session.isGroupChat) return
        val query = extractMentionQuery(text)
        if (query != null) {
            val candidates = allGroupParticipants.filter {
                query.isEmpty() || it.displayName.contains(query, ignoreCase = true)
            }.take(8)
            _uiState.update { it.copy(composer = it.composer.copy(mentionCandidates = candidates)) }
        } else {
            _uiState.update { it.copy(composer = it.composer.copy(mentionCandidates = emptyList())) }
        }
    }

    fun selectMention(user: User, currentText: String): String {
        val atIndex = currentText.lastIndexOf('@')
        val newText = if (atIndex >= 0) {
            currentText.substring(0, atIndex + 1) + user.displayName + " "
        } else {
            currentText
        }
        _uiState.update { it.copy(composer = it.composer.copy(mentionCandidates = emptyList())) }
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
            _uiState.update { it.copy(composer = it.composer.copy(isSending = true)) }
            listRepository.createList(title, type, chatId)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            composer = it.composer.copy(isSending = false),
                            session = it.session.copy(error = AppError.from(e))
                        )
                    }
                }
                .onSuccess { _uiState.update { it.copy(composer = it.composer.copy(isSending = false)) } }
        }
    }
}
