package com.firestream.chat.ui.stickers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.StickerPackPreview
import com.firestream.chat.domain.repository.StickerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StickerPackPreviewUiState(
    val isLoading: Boolean = true,
    /** The pack that was looked up, or `null` while it loads and when it could not be found. */
    val preview: StickerPackPreview? = null,
    val isAdding: Boolean = false,
    val error: AppError? = null,
)

/**
 * The pack a received sticker names, looked up and offered for adding. It
 * serves the sheet [StickerPackSheet], one pack at a time.
 */
@HiltViewModel
class StickerPackPreviewViewModel @Inject constructor(
    private val stickerRepository: StickerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StickerPackPreviewUiState())
    val uiState: StateFlow<StickerPackPreviewUiState> = _uiState.asStateFlow()

    private var loading: Job? = null

    /** Looks [packId] up afresh, each time the sheet opens. An earlier look-up still running is dropped. */
    fun load(packId: String) {
        loading?.cancel()
        _uiState.value = StickerPackPreviewUiState()
        loading = viewModelScope.launch {
            stickerRepository.viewPack(packId)
                .onSuccess { preview -> _uiState.update { it.copy(isLoading = false, preview = preview) } }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = AppError.from(e)) } }
        }
    }

    /** Adds the shown pack to the library. Does nothing for a pack that is there already. */
    fun addPack() {
        val state = _uiState.value
        val preview = state.preview ?: return
        if (preview.isInLibrary || state.isAdding) return
        _uiState.update { it.copy(isAdding = true, error = null) }
        viewModelScope.launch {
            stickerRepository.installPack(preview)
                .onSuccess {
                    // Only while the sheet still shows that pack: another one may have been opened meanwhile.
                    _uiState.update { current ->
                        if (current.preview?.packId != preview.packId) current
                        else current.copy(isAdding = false, preview = preview.copy(isInLibrary = true))
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(isAdding = false, error = AppError.from(e)) } }
        }
    }
}
