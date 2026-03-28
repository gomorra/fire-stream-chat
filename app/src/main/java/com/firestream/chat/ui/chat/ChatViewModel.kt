package com.firestream.chat.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.domain.usecase.chat.CheckGroupPermissionUseCase
import com.firestream.chat.domain.usecase.chat.GetChatsUseCase
import com.firestream.chat.domain.usecase.list.CreateListUseCase
import com.firestream.chat.domain.usecase.list.ObserveListUseCase
import com.firestream.chat.domain.usecase.message.AddReactionUseCase
import com.firestream.chat.domain.usecase.message.ClosePollUseCase
import com.firestream.chat.domain.usecase.message.DeleteMessageUseCase
import com.firestream.chat.domain.usecase.message.EditMessageUseCase
import com.firestream.chat.domain.usecase.message.ForwardMessageUseCase
import com.firestream.chat.domain.usecase.message.GetMessagesUseCase
import com.firestream.chat.domain.usecase.message.ParseMentionsUseCase
import com.firestream.chat.domain.usecase.message.PinMessageUseCase
import com.firestream.chat.domain.usecase.message.RemoveReactionUseCase
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase
import com.firestream.chat.domain.usecase.message.SendBroadcastMessageUseCase
import com.firestream.chat.domain.usecase.message.SendListMessageUseCase
import com.firestream.chat.domain.usecase.message.SendMediaMessageUseCase
import com.firestream.chat.domain.usecase.message.SendMessageUseCase
import com.firestream.chat.domain.usecase.message.SendPollUseCase
import com.firestream.chat.domain.usecase.message.SendVoiceMessageUseCase
import com.firestream.chat.domain.usecase.message.StarMessageUseCase
import com.firestream.chat.domain.usecase.message.VotePollUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentUserId: String = "",
    val isSending: Boolean = false,
    val typingUserIds: List<String> = emptyList(),
    val editingMessage: Message? = null,
    val replyToMessage: Message? = null,
    val linkPreviews: Map<String, LinkPreview> = emptyMap(),
    val availableChats: List<Chat> = emptyList(),
    val chatParticipants: Map<String, User> = emptyMap(),
    val searchQuery: String = "",
    val searchResults: List<Message> = emptyList(),
    val isSearchActive: Boolean = false,
    val readReceiptsAllowed: Boolean = true,
    val isGroupChat: Boolean = false,
    val chatName: String? = null,
    val canSendMessages: Boolean = true,
    val isAnnouncementMode: Boolean = false,
    val mentionCandidates: List<User> = emptyList(),
    val participantNameMap: Map<String, String> = emptyMap(),
    val isBroadcast: Boolean = false,
    val broadcastRecipientIds: List<String> = emptyList(),
    val recentEmojis: List<String> = emptyList(),
    val recipientAvatarUrl: String? = null,
    val isRecipientOnline: Boolean = false,
    val chatAvatarUrl: String? = null,
    val listDataCache: Map<String, ListData?> = emptyMap(),
    val pinnedMessages: List<Message> = emptyList()
) {
    val broadcastRecipientCount: Int get() = broadcastRecipientIds.size
    val avatarUrl: String? get() = recipientAvatarUrl ?: chatAvatarUrl
    val displayNameToUserId: Map<String, String> get() = participantNameMap.entries.associate { (k, v) -> v to k }
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
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
    private val pinMessageUseCase: PinMessageUseCase,
    private val sendListMessageUseCase: SendListMessageUseCase,
    private val createListUseCase: CreateListUseCase,
    private val observeListUseCase: ObserveListUseCase,
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

    val savedScrollIndex: Int get() = savedStateHandle["scrollIndex"] ?: -1
    val savedScrollOffset: Int get() = savedStateHandle["scrollOffset"] ?: 0

    fun saveScrollPosition(index: Int, offset: Int) {
        savedStateHandle["scrollIndex"] = index
        savedStateHandle["scrollOffset"] = offset
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Managers
    private val pollManager = ChatPollManager(
        chatId, sendPollUseCase, votePollUseCase, closePollUseCase, _uiState, viewModelScope
    )
    private val searchManager = ChatSearchManager(
        chatId, searchMessagesUseCase, _uiState, viewModelScope
    )
    private val messageActions = ChatMessageActions(
        chatId, deleteMessageUseCase, editMessageUseCase, addReactionUseCase,
        removeReactionUseCase, starMessageUseCase, pinMessageUseCase,
        forwardMessageUseCase, _uiState, viewModelScope
    )
    private val messageSender = ChatMessageSender(
        chatId, recipientId, sendMessageUseCase, sendMediaMessageUseCase,
        sendVoiceMessageUseCase, sendBroadcastMessageUseCase, parseMentionsUseCase,
        chatRepository, _uiState, viewModelScope
    )
    private val messageLoader = ChatMessageLoader(
        chatId, getMessagesUseCase, observeListUseCase, linkPreviewSource,
        chatRepository, messageRepository, context, _uiState, viewModelScope
    )
    private val infoManager = ChatInfoManager(
        chatId, recipientId, chatRepository, userRepository, preferencesDataStore,
        checkGroupPermissionUseCase, getChatsUseCase, createListUseCase, _uiState, viewModelScope
    )

    init {
        _uiState.update { it.copy(currentUserId = authRepository.currentUserId ?: "") }
        messageLoader.start()
        infoManager.start()
    }

    // ── Message loading & visibility ──
    fun setScreenVisible(visible: Boolean) = messageLoader.setScreenVisible(visible)

    // ── Message sending ──
    fun onTyping(text: String) = messageSender.onTyping(text)
    fun sendMessage(content: String, emojiSizes: Map<Int, Float> = emptyMap()) = messageSender.sendMessage(content, emojiSizes)
    fun sendMediaMessage(uri: Uri, mimeType: String) = messageSender.sendMediaMessage(uri, mimeType)
    fun sendVoiceMessage(uri: Uri, durationSeconds: Int) = messageSender.sendVoiceMessage(uri, durationSeconds)

    // ── Message actions ──
    fun deleteMessage(messageId: String) = messageActions.deleteMessage(messageId)
    fun startEdit(message: Message) = messageActions.startEdit(message)
    fun cancelEdit() = messageActions.cancelEdit()
    fun confirmEdit(newContent: String) = messageActions.confirmEdit(newContent)
    fun setReplyTo(message: Message) = messageActions.setReplyTo(message)
    fun clearReplyTo() = messageActions.clearReplyTo()
    fun toggleReaction(messageId: String, emoji: String) = messageActions.toggleReaction(messageId, emoji)
    fun forwardMessage(message: Message, targetChatId: String, targetRecipientId: String) =
        messageActions.forwardMessage(message, targetChatId, targetRecipientId)
    fun toggleStar(message: Message) = messageActions.toggleStar(message)
    fun togglePin(messageId: String, pinned: Boolean) = messageActions.togglePin(messageId, pinned)

    // ── Search ──
    fun onSearchQueryChange(query: String) = searchManager.onSearchQueryChange(query)
    fun toggleSearch() = searchManager.toggleSearch()
    fun clearSearch() = searchManager.clearSearch()

    // ── Polls ──
    fun sendPoll(question: String, options: List<String>, isMultipleChoice: Boolean, isAnonymous: Boolean) =
        pollManager.sendPoll(question, options, isMultipleChoice, isAnonymous)
    fun votePoll(messageId: String, optionIds: List<String>) = pollManager.votePoll(messageId, optionIds)
    fun closePoll(messageId: String) = pollManager.closePoll(messageId)

    // ── Mentions ──
    fun onTypingWithMentions(text: String) {
        messageSender.onTyping(text)
        infoManager.updateMentionCandidates(text)
    }
    fun selectMention(user: User, currentText: String): String = infoManager.selectMention(user, currentText)

    // ── Lists ──
    fun createAndSendList(title: String, type: ListType) = infoManager.createAndSendList(title, type)

    // ── Emoji ──
    fun addRecentEmoji(emoji: String) = infoManager.addRecentEmoji(emoji)

    // ── Error ──
    fun clearError() { _uiState.update { it.copy(error = null) } }

    override fun onCleared() {
        super.onCleared()
        messageSender.onCleared()
    }
}
