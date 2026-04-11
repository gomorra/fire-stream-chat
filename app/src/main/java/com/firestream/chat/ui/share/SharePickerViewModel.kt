package com.firestream.chat.ui.share

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.data.share.ShareContentResolver
import com.firestream.chat.data.share.SharedContentHolder
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.SharedContent
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PreviewState { Loading, Ready, Empty, Error }

data class SharePickerUiState(
    val chats: List<Chat> = emptyList(),
    val filteredChats: List<Chat> = emptyList(),
    val selectedChatIds: Set<String> = emptySet(),
    val sharedContent: SharedContent? = null,
    val linkPreview: LinkPreview? = null,
    val previewState: PreviewState = PreviewState.Loading,
    val searchQuery: String = "",
    val isSending: Boolean = false,
    val error: String? = null,
    val currentUserId: String = "",
    val participantProfiles: Map<String, User> = emptyMap()
)

@HiltViewModel
class SharePickerViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val linkPreviewSource: LinkPreviewSource,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val sharedContentHolder: SharedContentHolder,
    private val shareContentResolver: ShareContentResolver
) : ViewModel() {

    private val _uiState = MutableStateFlow(SharePickerUiState(
        currentUserId = authRepository.currentUserId ?: ""
    ))
    val uiState: StateFlow<SharePickerUiState> = _uiState.asStateFlow()

    init {
        loadChats()
        resolveSharedContent()
    }

    private fun loadChats() {
        viewModelScope.launch {
            val chats = chatRepository.getChats().first()
            _uiState.value = _uiState.value.copy(chats = chats, filteredChats = chats)
            loadParticipantProfiles(chats)
        }
    }

    private suspend fun loadParticipantProfiles(chats: List<Chat>) {
        val userId = _uiState.value.currentUserId
        val participantIds = chats
            .flatMap { it.participants }
            .filter { it != userId }
            .distinct()

        val profiles = coroutineScope {
            participantIds.map { id ->
                async { userRepository.getUserById(id).getOrNull()?.let { id to it } }
            }.awaitAll().filterNotNull().toMap()
        }

        _uiState.value = _uiState.value.copy(participantProfiles = profiles)
    }

    private fun resolveSharedContent() {
        val pendingIntent = sharedContentHolder.consumeIntent()
        if (pendingIntent == null) {
            _uiState.value = _uiState.value.copy(previewState = PreviewState.Empty)
            return
        }
        _uiState.value = _uiState.value.copy(previewState = PreviewState.Loading)
        viewModelScope.launch {
            val result = runCatching { shareContentResolver.resolve(pendingIntent) }
            result.fold(
                onSuccess = { content ->
                    if (content == null) {
                        _uiState.value = _uiState.value.copy(
                            previewState = PreviewState.Empty,
                            error = "Nothing to share"
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            sharedContent = content,
                            previewState = PreviewState.Ready
                        )
                        if (content is SharedContent.Text) {
                            val url = linkPreviewSource.extractUrl(content.text)
                            if (url != null) {
                                val preview = linkPreviewSource.fetchPreview(url)
                                _uiState.value = _uiState.value.copy(linkPreview = preview)
                            }
                        }
                    }
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        previewState = PreviewState.Error,
                        error = e.message ?: "Couldn't read shared content"
                    )
                }
            )
        }
    }

    fun toggleChatSelection(chatId: String) {
        val current = _uiState.value.selectedChatIds
        _uiState.value = _uiState.value.copy(
            selectedChatIds = if (chatId in current) current - chatId else current + chatId
        )
    }

    fun onSearchQueryChange(query: String) {
        val state = _uiState.value
        val filtered = if (query.isBlank()) {
            state.chats
        } else {
            state.chats.filter { chat ->
                val name = chatDisplayName(chat, state.currentUserId, state.participantProfiles)
                name.contains(query, ignoreCase = true)
            }
        }
        _uiState.value = state.copy(searchQuery = query, filteredChats = filtered)
    }

    private fun chatDisplayName(
        chat: Chat,
        currentUserId: String,
        profiles: Map<String, User>
    ): String {
        if (chat.name != null) return chat.name
        val recipientId = chat.participants.firstOrNull { it != currentUserId } ?: return "Chat"
        return profiles[recipientId]?.displayName?.takeIf { it.isNotBlank() } ?: recipientId
    }

    fun send(onDone: (singleChatId: String?, recipientId: String?) -> Unit) {
        val state = _uiState.value
        if (state.selectedChatIds.isEmpty() || state.isSending) return
        val content = state.sharedContent
        if (content == null) {
            _uiState.value = state.copy(error = "Nothing to share")
            return
        }

        _uiState.value = state.copy(isSending = true, error = null)

        viewModelScope.launch {
            val selectedChats = state.chats.filter { it.id in state.selectedChatIds }

            val results: List<Result<Unit>> = selectedChats.map { chat ->
                // Signal sessions are 1:1. Only pass a real recipientId for individual
                // chats; group/broadcast chats must go through the plaintext branch in
                // MessageRepositoryImpl so all participants can read the message.
                val recipientId = if (chat.type == ChatType.INDIVIDUAL) {
                    chat.participants.firstOrNull { it != state.currentUserId } ?: ""
                } else {
                    ""
                }
                async { sendToChat(chat.id, recipientId, content) }
            }.awaitAll()

            val failures = results.count { it.isFailure }
            val total = results.size

            if (failures == 0) {
                _uiState.value = _uiState.value.copy(isSending = false)
                if (selectedChats.size == 1) {
                    val chat = selectedChats[0]
                    val recipientId = if (chat.type == ChatType.INDIVIDUAL) {
                        chat.participants.firstOrNull { it != state.currentUserId } ?: ""
                    } else {
                        ""
                    }
                    onDone(chat.id, recipientId)
                } else {
                    onDone(null, null)
                }
            } else {
                val firstError = results.firstOrNull { it.isFailure }
                    ?.exceptionOrNull()
                    ?.message
                val message = when {
                    total == 1 -> firstError ?: "Couldn't share message"
                    failures == total -> "Couldn't share to any chat"
                    else -> "Couldn't share to $failures of $total chats"
                }
                _uiState.value = _uiState.value.copy(
                    isSending = false,
                    error = message
                )
            }
        }
    }

    /**
     * Send [content] to a single chat. Returns [Result.success] only when every
     * underlying repository call succeeded, so the caller can decide whether to
     * navigate away or surface a retry prompt.
     */
    private suspend fun sendToChat(
        chatId: String,
        recipientId: String,
        content: SharedContent
    ): Result<Unit> = runCatching {
        when (content) {
            is SharedContent.Text -> {
                messageRepository.sendMessage(chatId, content.text, recipientId).getOrThrow()
            }
            is SharedContent.Media -> coroutineScope {
                val results: List<Result<Message>> = content.items.map { item ->
                    async {
                        messageRepository.sendMediaMessage(
                            chatId,
                            Uri.parse(item.cachedUri),
                            item.mimeType,
                            recipientId
                        )
                    }
                }.awaitAll()
                val firstFailure = results.firstOrNull { it.isFailure }
                if (firstFailure != null) throw firstFailure.exceptionOrNull()!!
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
