package com.firestream.chat.ui.share

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.data.share.ShareContentResolver
import com.firestream.chat.data.share.SharedContentHolder
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.SharedContent
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.usecase.chat.GetChatsUseCase
import com.firestream.chat.domain.usecase.message.SendMediaMessageUseCase
import com.firestream.chat.domain.usecase.message.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SharePickerUiState(
    val chats: List<Chat> = emptyList(),
    val filteredChats: List<Chat> = emptyList(),
    val selectedChatIds: Set<String> = emptySet(),
    val sharedContent: SharedContent? = null,
    val linkPreview: LinkPreview? = null,
    val searchQuery: String = "",
    val isSending: Boolean = false,
    val error: String? = null,
    val currentUserId: String = ""
)

@HiltViewModel
class SharePickerViewModel @Inject constructor(
    private val getChatsUseCase: GetChatsUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val sendMediaMessageUseCase: SendMediaMessageUseCase,
    private val linkPreviewSource: LinkPreviewSource,
    private val authRepository: AuthRepository,
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
            val chats = getChatsUseCase().first()
            _uiState.value = _uiState.value.copy(chats = chats, filteredChats = chats)
        }
    }

    private fun resolveSharedContent() {
        val pendingIntent = sharedContentHolder.consumeIntent() ?: return
        viewModelScope.launch {
            val content = shareContentResolver.resolve(pendingIntent)
            _uiState.value = _uiState.value.copy(sharedContent = content)

            if (content is SharedContent.Text) {
                val url = linkPreviewSource.extractUrl(content.text)
                if (url != null) {
                    val preview = linkPreviewSource.fetchPreview(url)
                    _uiState.value = _uiState.value.copy(linkPreview = preview)
                }
            }
        }
    }

    fun toggleChatSelection(chatId: String) {
        val current = _uiState.value.selectedChatIds
        _uiState.value = _uiState.value.copy(
            selectedChatIds = if (chatId in current) current - chatId else current + chatId
        )
    }

    fun onSearchQueryChange(query: String) {
        val userId = _uiState.value.currentUserId
        val filtered = if (query.isBlank()) {
            _uiState.value.chats
        } else {
            _uiState.value.chats.filter { chat ->
                val name = chat.name ?: chat.participants.firstOrNull { it != userId } ?: ""
                name.contains(query, ignoreCase = true)
            }
        }
        _uiState.value = _uiState.value.copy(searchQuery = query, filteredChats = filtered)
    }

    fun send(onDone: (singleChatId: String?, recipientId: String?) -> Unit) {
        val state = _uiState.value
        if (state.selectedChatIds.isEmpty() || state.isSending) return

        _uiState.value = state.copy(isSending = true)

        viewModelScope.launch {
            val selectedChats = state.chats.filter { it.id in state.selectedChatIds }

            selectedChats.map { chat ->
                val recipientId = chat.participants.firstOrNull { it != state.currentUserId } ?: ""
                async {
                    runCatching {
                        when (val content = state.sharedContent) {
                            is SharedContent.Text ->
                                sendMessageUseCase(chat.id, content.text, recipientId)
                            is SharedContent.Media ->
                                content.items.map { item ->
                                    async {
                                        sendMediaMessageUseCase(
                                            chat.id,
                                            Uri.parse(item.cachedUri),
                                            item.mimeType,
                                            recipientId
                                        )
                                    }
                                }.awaitAll()
                            null -> Unit
                        }
                    }
                }
            }.awaitAll()

            _uiState.value = _uiState.value.copy(isSending = false)

            if (selectedChats.size == 1) {
                val chat = selectedChats[0]
                val recipientId = chat.participants.firstOrNull { it != state.currentUserId } ?: ""
                onDone(chat.id, recipientId)
            } else {
                onDone(null, null)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
