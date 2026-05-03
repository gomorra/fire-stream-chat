package com.firestream.chat.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.data.local.DictationLanguage
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.ScrollPos
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.command.CommandPayload
import com.firestream.chat.domain.command.CommandRegistry
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.data.remote.fcm.ActiveChatTracker
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.User
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.data.util.SpeechRecognizerManager
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.PollRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.domain.usecase.chat.CheckGroupPermissionUseCase
import com.firestream.chat.domain.usecase.message.SearchMessagesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

internal data class SnackbarEvent(
    val message: String,
    val actionLabel: String? = null,
    val actionUri: Uri? = null,
)

internal data class ChatUiState(
    val messages: MessagesState = MessagesState(),
    val composer: ComposerState = ComposerState(),
    val overlays: OverlaysState = OverlaysState(),
    val session: SessionState = SessionState(),
    val dictation: DictationState = DictationState(),
    val commands: CommandsState = CommandsState(),
) {
    val broadcastRecipientCount: Int get() = session.broadcastRecipientIds.size
    val avatarUrl: String? get() = session.recipientAvatarUrl ?: session.chatAvatarUrl
    val localAvatarPath: String? get() = session.recipientLocalAvatarPath ?: session.chatLocalAvatarPath
    val displayNameToUserId: Map<String, String> get() = session.participantNameMap.entries.associate { (k, v) -> v to k }
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val checkGroupPermissionUseCase: CheckGroupPermissionUseCase,
    private val searchMessagesUseCase: SearchMessagesUseCase,
    private val linkPreviewSource: LinkPreviewSource,
    private val authRepository: AuthRepository,
    private val chatRepository: ChatRepository,
    private val listRepository: ListRepository,
    private val messageRepository: MessageRepository,
    private val pollRepository: PollRepository,
    private val userRepository: UserRepository,
    private val preferencesDataStore: PreferencesDataStore,
    private val mediaFileManager: MediaFileManager,
    private val activeChatTracker: ActiveChatTracker,
    private val speechRecognizerManager: SpeechRecognizerManager,
    private val callStateHolder: CallStateHolder,
    private val commandRegistry: CommandRegistry,
    @ApplicationScope private val appScope: CoroutineScope,
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

    // Persist cross-process. @ApplicationScope (not viewModelScope) because writes
    // need to outlive onDispose when the user navigates away — viewModelScope is
    // cancelled before the DataStore edit lands.
    fun persistScrollPosition(index: Int, offset: Int) {
        appScope.launch {
            preferencesDataStore.setLastChatScroll(chatId, index, offset)
        }
    }

    suspend fun readPersistedScroll(): ScrollPos? =
        preferencesDataStore.lastChatScrollFlow.first()?.takeIf { it.chatId == chatId }

    // Shared mutable state. Handed to every Chat*Manager below — each manager owns a
    // conceptual slice of ChatUiState (messages, composer, overlays, session) and
    // mutates its own slice via `_uiState.update {}`. Managers never read or write
    // each other's slices and never call each other directly; coordination happens
    // only through this one StateFlow.
    private val _uiState = MutableStateFlow(ChatUiState())
    internal val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val uploadProgress: StateFlow<Map<String, Float>> = messageRepository.uploadProgress

    private val _snackbarEvent = MutableSharedFlow<SnackbarEvent>()
    internal val snackbarEvent: SharedFlow<SnackbarEvent> = _snackbarEvent.asSharedFlow()

    // Managers
    private val pollManager = ChatPollManager(chatId, pollRepository, _uiState, viewModelScope)
    private val searchManager = ChatSearchManager(chatId, searchMessagesUseCase, _uiState, viewModelScope)
    private val messageActions = ChatMessageActions(chatId, messageRepository, _uiState, viewModelScope)
    private val messageSender = ChatMessageSender(
        chatId, recipientId, chatRepository, messageRepository, _uiState, viewModelScope
    )
    private val messageLoader = ChatMessageLoader(
        chatId, listRepository, linkPreviewSource, chatRepository, messageRepository, context, _uiState, viewModelScope
    )
    private val infoManager = ChatInfoManager(
        chatId, recipientId, chatRepository, listRepository, userRepository, preferencesDataStore,
        checkGroupPermissionUseCase, _uiState, viewModelScope
    )
    private val dictationManager = ChatDictationManager(
        speechRecognizerManager, callStateHolder, context, _uiState, viewModelScope
    )
    private val commandsManager = ChatCommandsManager(commandRegistry, _uiState)

    // Latest persisted dictation language. Updated via collect of dictationLanguageFlow
    // so startDictation() can synchronously read it from the IconButton onClick path.
    @Volatile
    private var dictationLanguageTag: String = DictationLanguage.GERMAN.tag

    init {
        _uiState.update { it.copy(session = it.session.copy(currentUserId = authRepository.currentUserId ?: "")) }
        messageLoader.start()
        infoManager.start()
        dictationManager.init()
        viewModelScope.launch {
            preferencesDataStore.dictationLanguageFlow.collect { language ->
                dictationLanguageTag = language.tag
            }
        }
    }

    // ── Message loading & visibility ──
    fun setScreenVisible(visible: Boolean) {
        if (visible) activeChatTracker.setActive(chatId)
        else activeChatTracker.clearActive(chatId)
        messageLoader.setScreenVisible(visible)
    }
    fun setAtBottom(atBottom: Boolean) = messageLoader.setAtBottom(atBottom)

    // ── Block state ──
    fun refreshBlockState() = infoManager.refreshBlockState()

    // ── Message sending ──
    fun onTyping(text: String) = messageSender.onTyping(text)
    fun sendMessage(content: String, emojiSizes: Map<Int, Float> = emptyMap()) = messageSender.sendMessage(content, emojiSizes)
    fun sendMediaMessage(uri: Uri, mimeType: String, caption: String = "") = messageSender.sendMediaMessage(uri, mimeType, caption)
    fun sendVoiceMessage(uri: Uri, durationSeconds: Int) = messageSender.sendVoiceMessage(uri, durationSeconds)
    fun sendLocationMessage(latitude: Double, longitude: Double, comment: String = "") = messageSender.sendLocationMessage(latitude, longitude, comment)

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

    // ── Save to downloads ──
    fun saveImageToDownloads(localUri: String?, mediaUrl: String?, mimeType: String = "image/jpeg") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = when {
                    localUri != null && File(localUri).exists() -> File(localUri)
                    mediaUrl != null -> mediaFileManager.downloadAndSave(chatId, "download_${System.currentTimeMillis()}", mediaUrl)
                    else -> throw Exception("No image source available")
                }
                val uri = mediaFileManager.saveToDownloads(file, mimeType)
                _snackbarEvent.emit(SnackbarEvent("Image saved to Downloads", actionLabel = "Open", actionUri = uri))
            } catch (e: Exception) {
                _snackbarEvent.emit(SnackbarEvent("Failed to save: ${e.message}"))
            }
        }
    }

    // ── Dictation ──
    // Defaults to the Settings → Chat → Dictation Language preference (de-DE / en-US).
    fun startDictation(languageTag: String = dictationLanguageTag) =
        dictationManager.start(languageTag)
    fun stopDictation() = dictationManager.stop()
    fun cancelDictation() = dictationManager.cancel()
    fun clearDictationError() = dictationManager.clearError()
    internal val dictationCommits: SharedFlow<DictationCommit> get() = dictationManager.commits
    internal val dictationAudioLevel: StateFlow<Float> get() = dictationManager.audioLevel

    // ── Commands (.command palette + widgets) ──
    fun onComposerTextChangedForCommands(text: String) = commandsManager.onComposerTextChanged(text)
    fun openCommandPalette() = commandsManager.openPalette()
    fun closeCommandPalette() = commandsManager.closePalette()
    fun navigateIntoCommand(commandId: String) = commandsManager.navigateInto(commandId)
    fun navigateBackInCommands() = commandsManager.navigateBack()
    fun updateCommandFilter(text: String) = commandsManager.updateFilter(text)
    fun dismissCommandWidget() = commandsManager.dismissWidget()

    fun onCommandSubmit(payload: CommandPayload) {
        commandsManager.dismissWidget()
        when (payload) {
            is CommandPayload.Timer -> sendTimerCommand(payload)
        }
    }

    private fun sendTimerCommand(payload: CommandPayload.Timer) {
        viewModelScope.launch {
            _uiState.update { it.copy(composer = it.composer.copy(isSending = true)) }
            messageRepository.sendTimerMessage(chatId, payload.durationMs, payload.caption, recipientId)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            composer = it.composer.copy(isSending = false),
                            session = it.session.copy(error = AppError.from(e)),
                        )
                    }
                }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            composer = it.composer.copy(isSending = false),
                            messages = it.messages.copy(scrollToBottomTrigger = it.messages.scrollToBottomTrigger + 1),
                        )
                    }
                }
        }
    }

    // ── Error ──
    fun clearError() { _uiState.update { it.copy(session = it.session.copy(error = null)) } }

    override fun onCleared() {
        super.onCleared()
        messageSender.onCleared()
        dictationManager.cancel()
        activeChatTracker.clearActive(chatId)
    }
}
