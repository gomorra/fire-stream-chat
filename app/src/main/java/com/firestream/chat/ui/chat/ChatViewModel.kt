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
import com.firestream.chat.data.timer.ScheduleResult
import com.firestream.chat.data.timer.TimerAlarmScheduler
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.command.CommandPayload
import com.firestream.chat.domain.command.CommandRegistry
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.data.remote.fcm.ActiveChatTracker
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.ReminderScheduleOutcome
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.reminder.DateTimeDetector
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.data.util.SpeechRecognizerManager
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.PollRepository
import com.firestream.chat.domain.repository.ReminderRepository
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

/** Resolution state of the cross-process persisted scroll position (DataStore). */
internal sealed interface PersistedScrollState {
    data object Loading : PersistedScrollState
    /** [pos] is null when nothing is persisted for this chat. */
    data class Ready(val pos: ScrollPos?) : PersistedScrollState
}

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
    private val reminderRepository: ReminderRepository,
    private val dateTimeDetector: DateTimeDetector,
    private val pollRepository: PollRepository,
    private val userRepository: UserRepository,
    private val preferencesDataStore: PreferencesDataStore,
    private val mediaFileManager: MediaFileManager,
    private val activeChatTracker: ActiveChatTracker,
    private val speechRecognizerManager: SpeechRecognizerManager,
    private val callStateHolder: CallStateHolder,
    private val commandRegistry: CommandRegistry,
    private val timerAlarmScheduler: TimerAlarmScheduler,
    @ApplicationScope private val appScope: CoroutineScope,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val chatId: String = checkNotNull(savedStateHandle["chatId"])
    val recipientId: String = checkNotNull(savedStateHandle["recipientId"])

    // Set when the chat is opened from a reminder/message notification deep link
    // (see Routes.chat(targetMessageId = …)). ChatScreen consumes it once to
    // scroll to and flash that message; null for an ordinary open. The route
    // always carries the query key (empty when absent), so blank ⇒ null (mapped
    // in ChatScreen). Exposed as a StateFlow, not a one-shot read: a warm
    // notification tap for a chat that is already on top re-navigates
    // launchSingleTop, which reuses this ViewModel — a plain `val` read at init
    // would keep the stale target and the jump would never fire.
    val targetMessageId: StateFlow<String?> =
        savedStateHandle.getStateFlow("targetMessageId", null)

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

    // Prefetched in init so the value is already resolved by the time Room's
    // first message emission reaches the screen — the initial scroll position
    // must be known BEFORE the list first composes with data (jump-free first
    // frame; see the SideEffect in ChatScreen).
    private val _persistedScrollState =
        MutableStateFlow<PersistedScrollState>(PersistedScrollState.Loading)
    internal val persistedScrollState: StateFlow<PersistedScrollState> = _persistedScrollState.asStateFlow()

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
    private val messageActions = ChatMessageActions(
        chatId, recipientId, messageRepository, reminderRepository, dateTimeDetector, _uiState, viewModelScope,
        onReminderScheduled = { outcome ->
            if (outcome == ReminderScheduleOutcome.INEXACT_FALLBACK) {
                commandsManager.setExactAlarmBannerVisible(true)
            }
        },
    )
    private val messageSender = ChatMessageSender(
        chatId, recipientId, chatRepository, messageRepository, _uiState, viewModelScope
    )
    private val messageLoader = ChatMessageLoader(
        chatId, listRepository, linkPreviewSource, chatRepository, messageRepository, reminderRepository,
        context, _uiState, viewModelScope
    )

    // Reactions another user just added to one of my messages, forwarded to the
    // chat screen so it can flash the bubble or show a jump-to-reaction FAB.
    internal val reactionAlerts: SharedFlow<ReactionAlert> get() = messageLoader.reactionAlerts
    private val infoManager = ChatInfoManager(
        chatId, recipientId, chatRepository, listRepository, userRepository, preferencesDataStore,
        checkGroupPermissionUseCase, _uiState, viewModelScope
    )
    private val dictationManager = ChatDictationManager(
        speechRecognizerManager, callStateHolder, context, _uiState, viewModelScope
    )
    private val commandsManager = ChatCommandsManager(commandRegistry, _uiState)
    private val timerReactor = ChatTimerReactor(
        chatId = chatId,
        recipientId = recipientId,
        scheduler = timerAlarmScheduler,
        _uiState = _uiState,
        scope = viewModelScope,
        onScheduleResult = { result ->
            if (result == ScheduleResult.INEXACT_FALLBACK) {
                commandsManager.setExactAlarmBannerVisible(true)
            }
        },
    )

    // Latest persisted dictation language. Updated via collect of dictationLanguageFlow
    // so startDictation() can synchronously read it from the IconButton onClick path.
    @Volatile
    private var dictationLanguageTag: String = DictationLanguage.GERMAN.tag

    init {
        _uiState.update { it.copy(session = it.session.copy(currentUserId = authRepository.currentUserId ?: "")) }
        viewModelScope.launch {
            val pos = preferencesDataStore.lastChatScrollFlow.first()?.takeIf { it.chatId == chatId }
            _persistedScrollState.value = PersistedScrollState.Ready(pos)
        }
        messageLoader.start()
        infoManager.start()
        dictationManager.init()
        timerReactor.start()
        // Single authoritative "last location" write: every entry path into a
        // chat (list click, notification tap, restore, share, forward) makes
        // this chat the restore target. NavGraph clears it when the user rests
        // on the chat list. @ApplicationScope so the write survives immediate
        // process death after entry.
        appScope.launch {
            preferencesDataStore.setLastOpenChat(chatId, recipientId)
        }
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
    fun retrySend(message: Message) = messageSender.retrySend(message)

    // ── Message actions ──
    fun deleteMessage(messageId: String) = messageActions.deleteMessage(messageId)
    fun startEdit(message: Message) = messageActions.startEdit(message)
    fun cancelEdit() = messageActions.cancelEdit()
    fun confirmEdit(newContent: String, emojiSizes: Map<Int, Float> = emptyMap()) = messageActions.confirmEdit(newContent, emojiSizes)
    fun setReplyTo(message: Message) = messageActions.setReplyTo(message)
    fun clearReplyTo() = messageActions.clearReplyTo()
    fun toggleReaction(messageId: String, emoji: String) = messageActions.toggleReaction(messageId, emoji)
    fun forwardMessage(message: Message, targetChatId: String, targetRecipientId: String) =
        messageActions.forwardMessage(message, targetChatId, targetRecipientId)
    fun toggleStar(message: Message) = messageActions.toggleStar(message)
    fun togglePin(messageId: String, pinned: Boolean) = messageActions.togglePin(messageId, pinned)
    fun snoozeMessage(message: Message, fireAtMs: Long) = messageActions.snoozeMessage(message, fireAtMs)
    fun cancelReminder(messageId: String) = messageActions.cancelReminder(messageId)
    suspend fun detectSnoozeTime(text: String): Long? = messageActions.detectSnoozeTime(text)

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
    fun dismissExactAlarmBanner() = commandsManager.setExactAlarmBannerVisible(false)

    fun onCommandSubmit(payload: CommandPayload) {
        commandsManager.dismissWidget()
        when (payload) {
            is CommandPayload.Timer -> sendTimerCommand(payload)
        }
    }

    private fun sendTimerCommand(payload: CommandPayload.Timer) {
        viewModelScope.launch {
            _uiState.update { it.copy(composer = it.composer.copy(isSending = true)) }
            messageRepository.sendTimerMessage(chatId, payload.durationMs, payload.caption, recipientId, payload.silent)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            composer = it.composer.copy(isSending = false),
                            session = it.session.copy(error = AppError.from(e)),
                        )
                    }
                }
                .onSuccess {
                    // ChatTimerReactor sees the new RUNNING TIMER message via the
                    // Room flow and schedules the alarm — no inline scheduling here.
                    _uiState.update {
                        it.copy(
                            composer = it.composer.copy(isSending = false),
                            messages = it.messages.copy(scrollToBottomTrigger = it.messages.scrollToBottomTrigger + 1),
                        )
                    }
                }
        }
    }

    fun cancelTimer(messageId: String) {
        viewModelScope.launch {
            messageRepository.cancelTimer(chatId, messageId)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(session = it.session.copy(error = AppError.from(e)))
                    }
                }
            // ChatTimerReactor observes the resulting CANCELLED state and cancels
            // the local AlarmManager entry; the recipient's reactor does the same
            // when Firestore syncs the state change.
        }
    }

    /**
     * Pause a running timer. [remainingMs] is computed by the caller (the bubble's
     * live countdown) so the repo can snapshot the exact frozen value.
     */
    fun pauseTimer(messageId: String, remainingMs: Long) {
        viewModelScope.launch {
            messageRepository.pauseTimer(chatId, messageId, remainingMs)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(session = it.session.copy(error = AppError.from(e)))
                    }
                }
        }
    }

    /** Resume a paused timer from its stored [timerRemainingMs]. */
    fun resumeTimer(messageId: String) {
        viewModelScope.launch {
            messageRepository.resumeTimer(chatId, messageId)
                .onFailure { e ->
                    _uiState.update {
                        it.copy(session = it.session.copy(error = AppError.from(e)))
                    }
                }
        }
    }

    // ── Error ──
    fun clearError() { _uiState.update { it.copy(session = it.session.copy(error = null)) } }

    // ── Fullscreen image viewer ──
    // ViewModel state (not screen-local compose state) so the open viewer
    // survives activity recreation on rotation. `internal` because the
    // FullscreenImage parameter type is internal (like `uiState` above).
    internal fun showFullscreenImage(image: FullscreenImage) {
        _uiState.update { it.copy(overlays = it.overlays.copy(fullscreenImage = image)) }
    }

    internal fun dismissFullscreenImage() {
        _uiState.update { it.copy(overlays = it.overlays.copy(fullscreenImage = null)) }
    }

    // ── Fullscreen video player ──
    // Mirrors the fullscreen-image pair above: ViewModel state so the open
    // player survives activity recreation on rotation.
    internal fun showFullscreenVideo(source: String) {
        _uiState.update { it.copy(overlays = it.overlays.copy(fullscreenVideo = FullscreenVideo(source = source))) }
    }

    internal fun dismissFullscreenVideo() {
        _uiState.update { it.copy(overlays = it.overlays.copy(fullscreenVideo = null)) }
    }

    override fun onCleared() {
        super.onCleared()
        messageSender.onCleared()
        dictationManager.cancel()
        activeChatTracker.clearActive(chatId)
    }
}
