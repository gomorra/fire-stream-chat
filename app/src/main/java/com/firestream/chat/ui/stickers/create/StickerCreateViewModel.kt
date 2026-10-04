package com.firestream.chat.ui.stickers.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.StickerCrop
import com.firestream.chat.domain.model.StickerDraft
import com.firestream.chat.domain.model.StickerDraftImage
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.domain.repository.StickerRepository
import com.firestream.chat.domain.util.StickerGeometry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StickerCreateUiState(
    /** A picked photo is being decoded and its subject cut out. */
    val isPreparing: Boolean = false,
    val draft: StickerDraft? = null,
    /** Whether the sticker is the subject alone. Ignored for a draft without a cutout. */
    val useCutout: Boolean = true,
    val outline: Boolean = true,
    val crop: StickerCrop = StickerCrop(),
    val emojis: List<String> = emptyList(),
    /** The packs a made sticker can join: the user's own. */
    val packs: List<StickerPack> = emptyList(),
    /** The pack the sticker joins, or `null` for the pack named [newPackName]. */
    val packId: String? = null,
    val newPackName: String = StickerDraft.DEFAULT_PACK_NAME,
    val isSaving: Boolean = false,
    /** The sticker is in the library, and the screen closes. */
    val isDone: Boolean = false,
    val error: AppError? = null,
) {
    val hasCutout: Boolean get() = draft?.cutout != null

    /** The picture the choices amount to: what the preview shows and what is saved. */
    val image: StickerDraftImage?
        get() = draft?.let { draft ->
            when {
                !useCutout || draft.cutout == null -> draft.original
                outline -> draft.outlined ?: draft.cutout
                else -> draft.cutout
            }
        }

    val canAddEmoji: Boolean get() = emojis.size < StickerDraft.MAX_EMOJIS

    val canSave: Boolean
        get() = image != null && !isSaving && !isPreparing && (packId != null || newPackName.isNotBlank())
}

/**
 * The sticker maker: a photo, its cutout, the crop, the emojis and the pack.
 * It sees [StickerRepository] only. The composable owns the photo picker and
 * hands its uri over as a string.
 */
@HiltViewModel
class StickerCreateViewModel @Inject constructor(
    private val stickerRepository: StickerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StickerCreateUiState())
    val uiState: StateFlow<StickerCreateUiState> = _uiState.asStateFlow()

    private var prepareJob: Job? = null

    /** False until the user picks a pack, so the first library emission may pick one for them. */
    private var packChosen = false

    init {
        viewModelScope.launch {
            stickerRepository.observePacks()
                .catch { e -> _uiState.update { it.copy(error = AppError.from(e)) } }
                .collect { all ->
                    val packs = all.filter { it.kind == StickerPackKind.USER }
                    _uiState.update { state ->
                        val packId = when {
                            // A sticker made earlier went to the default pack. The next one joins it.
                            !packChosen -> packs.firstOrNull { it.name == StickerDraft.DEFAULT_PACK_NAME }?.id
                            else -> state.packId?.takeIf { id -> packs.any { it.id == id } }
                        }
                        state.copy(packs = packs, packId = packId)
                    }
                }
        }
    }

    /** Prepares the picked photo. A photo picked while another is prepared replaces it. */
    fun onPhotoPicked(uri: String) {
        prepareJob?.cancel()
        _uiState.update { it.copy(isPreparing = true, draft = null, crop = StickerCrop(), useCutout = true, outline = true) }
        prepareJob = viewModelScope.launch {
            stickerRepository.prepareStickerDraft(uri)
                .onSuccess { draft -> _uiState.update { it.copy(isPreparing = false, draft = draft) } }
                .onFailure { e -> _uiState.update { it.copy(isPreparing = false, error = AppError.from(e)) } }
        }
    }

    /** The cutout and the photo differ in shape, so a crop made on one says nothing about the other. */
    fun setUseCutout(useCutout: Boolean) = _uiState.update {
        if (it.useCutout == useCutout) it else it.copy(useCutout = useCutout, crop = StickerCrop())
    }

    fun setOutline(outline: Boolean) = _uiState.update { state ->
        // The outlined picture is a little larger, so the crop's limits move with it.
        state.copy(outline = outline).run { copy(crop = image?.let { StickerGeometry.clamp(crop, it.width, it.height) } ?: crop) }
    }

    /** A pinch by [zoom] and a drag by [panX], [panY] in fractions of the preview's side. */
    fun transform(zoom: Float, panX: Float, panY: Float) = _uiState.update { state ->
        val image = state.image ?: return@update state
        state.copy(crop = StickerGeometry.transformed(state.crop, zoom, panX, panY, image.width, image.height))
    }

    fun addEmoji(emoji: String) = _uiState.update {
        if (!it.canAddEmoji || emoji in it.emojis) it else it.copy(emojis = it.emojis + emoji)
    }

    fun removeEmoji(emoji: String) = _uiState.update { it.copy(emojis = it.emojis - emoji) }

    /** [packId] is one of the user's packs, or `null` for a new one. */
    fun choosePack(packId: String?) {
        packChosen = true
        _uiState.update { it.copy(packId = packId) }
    }

    fun setNewPackName(name: String) = _uiState.update { it.copy(newPackName = name.take(MAX_PACK_NAME)) }

    fun save() {
        val state = _uiState.value
        val image = state.image ?: return
        if (!state.canSave) return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            stickerRepository.createSticker(image.path, state.crop, state.emojis, state.packId, state.newPackName)
                .onSuccess { _uiState.update { it.copy(isSaving = false, isDone = true) } }
                .onFailure { e -> _uiState.update { it.copy(isSaving = false, error = AppError.from(e)) } }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    companion object {
        const val MAX_PACK_NAME = 40
    }
}
