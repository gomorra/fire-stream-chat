package com.firestream.chat.ui.stickers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.WhatsAppStickerFile
import com.firestream.chat.domain.repository.StickerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The WhatsApp sticker folder as it is offered for import. [selected] holds file uris. */
data class WhatsAppImportState(
    val isLoading: Boolean = true,
    val files: List<WhatsAppStickerFile> = emptyList(),
    val selected: Set<String> = emptySet(),
) {
    val allSelected: Boolean get() = files.isNotEmpty() && selected.size == files.size
}

data class StickerLibraryUiState(
    val isLoading: Boolean = true,
    val packs: List<StickerPack> = emptyList(),
    /** The pack whose grid is shown, or `null` for the list of packs. */
    val openPackId: String? = null,
    /** The stickers picked in the open pack's grid, to move or remove. */
    val selectedStickerIds: Set<String> = emptySet(),
    /** The WhatsApp folder view, shown over everything else while it is not `null`. */
    val whatsApp: WhatsAppImportState? = null,
    val isImporting: Boolean = false,
    /** What the last import did, shown once. */
    val notice: String? = null,
    val error: AppError? = null,
) {
    val openPack: StickerPack? get() = packs.firstOrNull { it.id == openPackId }
}

/**
 * The sticker library screen and both of its import routes. It sees
 * [StickerRepository] only: the composable owns the pickers and hands their
 * uris over as strings.
 */
@HiltViewModel
class StickerLibraryViewModel @Inject constructor(
    private val stickerRepository: StickerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StickerLibraryUiState())
    val uiState: StateFlow<StickerLibraryUiState> = _uiState.asStateFlow()

    init {
        // The one collector of the library: every emission maps all of it.
        viewModelScope.launch {
            stickerRepository.observePacks()
                .catch { e -> _uiState.update { it.copy(isLoading = false, error = AppError.from(e)) } }
                .collect { packs ->
                    _uiState.update { state ->
                        val open = packs.firstOrNull { it.id == state.openPackId }
                        val present = open?.stickers.orEmpty().mapTo(HashSet()) { it.id }
                        state.copy(
                            isLoading = false,
                            packs = packs,
                            openPackId = open?.id,
                            selectedStickerIds = state.selectedStickerIds.filterTo(LinkedHashSet()) { it in present },
                        )
                    }
                }
        }
    }

    fun openPack(packId: String) = _uiState.update { it.copy(openPackId = packId, selectedStickerIds = emptySet()) }

    fun closePack() = _uiState.update { it.copy(openPackId = null, selectedStickerIds = emptySet()) }

    fun toggleSticker(stickerId: String) = _uiState.update { it.copy(selectedStickerIds = it.selectedStickerIds.toggle(stickerId)) }

    fun clearSelection() = _uiState.update { it.copy(selectedStickerIds = emptySet()) }

    fun removeSelected() = editSelection { packId, ids -> stickerRepository.removeStickers(packId, ids) }

    fun moveSelectedTo(targetPackId: String) =
        editSelection { packId, ids -> stickerRepository.moveStickers(ids, packId, targetPackId) }

    /** Runs [block] on the open pack and its selected stickers, then clears the selection. */
    private fun editSelection(block: suspend (packId: String, stickerIds: List<String>) -> Result<Unit>) {
        val state = _uiState.value
        val packId = state.openPackId ?: return
        val ids = state.selectedStickerIds.toList()
        if (ids.isEmpty()) return
        edit { block(packId, ids) }
        clearSelection()
    }

    fun renamePack(packId: String, name: String) = edit { stickerRepository.renamePack(packId, name) }

    fun deletePack(packId: String) = edit { stickerRepository.deletePack(packId) }

    /** Swaps the pack with its neighbour above or below. Does nothing at either end of the list. */
    fun movePack(packId: String, up: Boolean) {
        val ids = _uiState.value.packs.map { it.id }.toMutableList()
        val from = ids.indexOf(packId)
        val to = if (up) from - 1 else from + 1
        if (from < 0 || to !in ids.indices) return
        ids[from] = ids[to]
        ids[to] = packId
        edit { stickerRepository.reorderPacks(ids) }
    }

    /** Imports the picked files and archives. Stickers that name no pack go to the `SAVED` pack. */
    fun importFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        import(uris, loosePackName = null, skipKnown = false)
    }

    /** Shows the folder the user just granted, with nothing selected. */
    fun openWhatsAppFolder(treeUri: String) {
        _uiState.update { it.copy(whatsApp = WhatsAppImportState()) }
        viewModelScope.launch {
            stickerRepository.listWhatsAppFolder(treeUri)
                .onSuccess { files ->
                    // Closed while the listing ran: stay closed.
                    _uiState.update { it.copy(whatsApp = it.whatsApp?.copy(isLoading = false, files = files)) }
                }
                .onFailure { e -> _uiState.update { it.copy(whatsApp = null, error = AppError.from(e)) } }
        }
    }

    fun closeWhatsApp() = _uiState.update { it.copy(whatsApp = null) }

    fun toggleWhatsAppFile(uri: String) =
        _uiState.update { it.copy(whatsApp = it.whatsApp?.let { wa -> wa.copy(selected = wa.selected.toggle(uri)) }) }

    /** Selects every file, or none when every file is already selected. */
    fun toggleSelectAllWhatsApp() = _uiState.update { state ->
        state.copy(
            whatsApp = state.whatsApp?.let { wa ->
                wa.copy(selected = if (wa.allSelected) emptySet() else wa.files.mapTo(HashSet()) { it.uri })
            }
        )
    }

    fun importSelectedWhatsApp() {
        val wa = _uiState.value.whatsApp ?: return
        // In the folder's order, which is newest first.
        val uris = wa.files.map { it.uri }.filter { it in wa.selected }
        if (uris.isEmpty()) return
        // The folder holds every sticker ever seen, so what the library knows is left where it is.
        import(uris, loosePackName = WHATSAPP_PACK_NAME, skipKnown = true)
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    fun clearNotice() = _uiState.update { it.copy(notice = null) }

    /** A finished import closes the WhatsApp view, the only place one can start from while it is open. */
    private fun import(uris: List<String>, loosePackName: String?, skipKnown: Boolean) {
        if (_uiState.value.isImporting) return
        _uiState.update { it.copy(isImporting = true) }
        viewModelScope.launch {
            stickerRepository.importFrom(uris, loosePackName, skipKnown)
                .onSuccess { result ->
                    _uiState.update { it.copy(isImporting = false, notice = importSummary(result), whatsApp = null) }
                }
                .onFailure { e -> _uiState.update { it.copy(isImporting = false, error = AppError.from(e)) } }
        }
    }

    private fun edit(block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            block().onFailure { e -> _uiState.update { it.copy(error = AppError.from(e)) } }
        }
    }

    private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value

    companion object {
        /**
         * The pack that WhatsApp stickers without pack metadata join. The repository
         * stores it in that pack's import key, and the key is backed up with the
         * pack. So this value must not change: a changed name starts a second pack
         * beside the restored one. A translated name has to come from the repository.
         * `StickerPackEntity.LOOSE_WHATSAPP_KEY` is the key it makes.
         */
        const val WHATSAPP_PACK_NAME = "WhatsApp"
    }
}
