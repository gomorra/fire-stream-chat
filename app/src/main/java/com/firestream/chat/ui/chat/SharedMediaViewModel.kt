package com.firestream.chat.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SharedMediaViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val chatId: String = checkNotNull(savedStateHandle["chatId"])

    val media: StateFlow<List<SharedMediaItem>> = messageRepository.getMessages(chatId)
        .map { messages ->
            messages
                .filter { it.type == MessageType.IMAGE && it.mediaUrl != null && it.deletedAt == null }
                .sortedByDescending { it.timestamp }
                .map { SharedMediaItem(mediaUrl = it.mediaUrl!!, localUri = it.localUri) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Guarantee a durable local copy of every shared image the moment the
        // gallery opens, so remote-only images stop re-downloading on each
        // re-entry. Launched on @ApplicationScope (not viewModelScope) so the
        // saves finish even if the user backs out immediately.
        appScope.launch { messageRepository.ensureLocalCopiesForChat(chatId) }
    }
}

/**
 * A single image shown in the Shared Media grid. Carries the on-disk [localUri]
 * (when the full file has been downloaded) alongside the remote [mediaUrl] so
 * the grid can decode the local copy and hand it to the fullscreen viewer,
 * mirroring the local-first resolution used everywhere else in the app.
 */
data class SharedMediaItem(val mediaUrl: String, val localUri: String?)
