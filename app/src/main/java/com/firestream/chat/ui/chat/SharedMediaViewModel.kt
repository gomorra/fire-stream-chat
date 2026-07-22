package com.firestream.chat.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SharedMediaViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    messageRepository: MessageRepository
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
}

/**
 * A single image shown in the Shared Media grid. Carries the on-disk [localUri]
 * (when the full file has been downloaded) alongside the remote [mediaUrl] so
 * the grid can decode the local copy and hand it to the fullscreen viewer,
 * mirroring the local-first resolution used everywhere else in the app.
 */
data class SharedMediaItem(val mediaUrl: String, val localUri: String?)
