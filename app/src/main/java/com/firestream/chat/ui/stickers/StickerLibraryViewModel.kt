package com.firestream.chat.ui.stickers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
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

/**
 * The WhatsApp sticker folder as it is offered for import. [selected] holds file uris.
 *
 * [importedUntil] is the newest `lastModified` the folder held at the last
 * finished import, or 0 before the first one. Only the files newer than that
 * are shown, until [showAll] is switched on.
 */
data class WhatsAppImportState(
    val isLoading: Boolean = true,
    val files: List<WhatsAppStickerFile> = emptyList(),
    val selected: Set<String> = emptySet(),
    val importedUntil: Long = 0,
    val showAll: Boolean = false,
) {
    /** Whether there is an earlier import to tell new files from. Without one every file is shown, and there is no switch. */
    val hasEarlierImport: Boolean get() = importedUntil > 0

    /** The files the grid shows, in the folder's order. */
    val shownFiles: List<WhatsAppStickerFile> =
        if (showAll || importedUntil <= 0) files else files.filter { it.lastModified > importedUntil }

    val allSelected: Boolean get() = shownFiles.isNotEmpty() && shownFiles.all { it.uri in selected }
}

/** One sticker in one pack. A sticker that two packs hold is two entries. */
data class StickerEntry(val packId: String, val stickerId: String)

/** The two tabs of the sticker manager. */
enum class StickerManagerTab(val title: String) {
    PACKS("Packs"),
    ALL_STICKERS("All stickers"),
}

data class StickerLibraryUiState(
    val isLoading: Boolean = true,
    val packs: List<StickerPack> = emptyList(),
    val tab: StickerManagerTab = StickerManagerTab.PACKS,
    /** The packs picked in the list, to merge or delete. Only packs with a name of their own. */
    val selectedPackIds: Set<String> = emptySet(),
    /** The pack whose grid is shown, or `null` for the list of packs. */
    val openPackId: String? = null,
    /**
     * The stickers picked in a grid. In the open pack's grid every entry names
     * that pack. In the grid of every sticker a selection can span packs.
     */
    val selectedStickers: Set<StickerEntry> = emptySet(),
    /** What the search field of the *All stickers* tab holds. */
    val query: String = "",
    /** The WhatsApp folder view, shown over everything else while it is not `null`. */
    val whatsApp: WhatsAppImportState? = null,
    val isImporting: Boolean = false,
    /** What the last import did, shown once. */
    val notice: String? = null,
    val error: AppError? = null,
) {
    val openPack: StickerPack? get() = packs.firstOrNull { it.id == openPackId }

    /**
     * The selected packs in the order the list shows them: those with their own
     * thumbnail first, each group in the user's order. A merge keeps the first.
     */
    val selectedPacks: List<StickerPack>
        get() = packs.filter { it.id in selectedPackIds }.sortedBy { !it.hasOwnThumbnail }

    /** The repository refuses a merge of fewer than two packs. */
    val canMerge: Boolean get() = selectedPackIds.size >= 2

    /** How many stickers the selection holds. A sticker picked in two packs counts once. */
    val selectedStickerCount: Int get() = selectedStickers.mapTo(HashSet()) { it.stickerId }.size

    /**
     * The packs the selection can move to. Not *Favourites*, which has its own
     * action, and not the pack that all of the selection is in.
     */
    val moveTargets: List<StickerPack>
        get() {
            val only = selectedStickers.mapTo(HashSet()) { it.packId }.singleOrNull()
            return packs.filter { it.kind != StickerPackKind.FAVOURITES && it.id != only }
        }
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

    /**
     * The library as the repository last sent it. A dropped order that could not
     * be saved goes back to it. Declared above `init`, whose collector can run at once.
     */
    private var observedPacks: List<StickerPack> = emptyList()

    init {
        // The one collector of the library: every emission maps all of it.
        viewModelScope.launch {
            stickerRepository.observePacks()
                .catch { e -> _uiState.update { it.copy(isLoading = false, error = AppError.from(e)) } }
                .collect { packs ->
                    observedPacks = packs
                    _uiState.update { state ->
                        val open = packs.firstOrNull { it.id == state.openPackId }
                        val selectable = packs.filter { it.kind.isNamed }.mapTo(HashSet()) { it.id }
                        state.copy(
                            isLoading = false,
                            packs = packs,
                            openPackId = open?.id,
                            selectedStickers = state.selectedStickers.heldBy(packs),
                            selectedPackIds = state.selectedPackIds.filterTo(LinkedHashSet()) { it in selectable },
                        )
                    }
                }
        }
    }

    /** A selection belongs to the tab it was made in, so changing the tab ends it. */
    fun selectTab(tab: StickerManagerTab) =
        _uiState.update { it.copy(tab = tab, selectedPackIds = emptySet(), selectedStickers = emptySet()) }

    fun setQuery(query: String) = _uiState.update { it.copy(query = query) }

    /** Adds the pack to the selection or takes it out. *Favourites* and *Saved stickers* cannot be merged, so they are never selected. */
    fun togglePack(packId: String) = _uiState.update { state ->
        val pack = state.packs.firstOrNull { it.id == packId }
        if (pack == null || !pack.kind.isNamed) state else state.copy(selectedPackIds = state.selectedPackIds.toggle(packId))
    }

    fun clearPackSelection() = _uiState.update { it.copy(selectedPackIds = emptySet()) }

    /** Makes one pack called [name] of the selected packs. The first one in the list keeps its place. */
    fun mergeSelected(name: String) {
        val state = _uiState.value
        if (!state.canMerge) return
        val ids = state.selectedPacks.map { it.id }
        edit { stickerRepository.mergePacks(ids, name) }
        clearPackSelection()
    }

    /** Deletes the selected packs one after the other, and stops at the first that fails. */
    fun deleteSelected() {
        val ids = _uiState.value.selectedPacks.map { it.id }
        if (ids.isEmpty()) return
        edit { ids.untilFailure { stickerRepository.deletePack(it) } }
        clearPackSelection()
    }

    fun setPackShownInRow(packId: String, shown: Boolean) = edit { stickerRepository.setPackShownInRow(packId, shown) }

    /**
     * Saves the order a drag left the packs in. The list shows it at once, so the
     * dropped row does not jump back while the write runs.
     */
    fun reorderPacks(packIds: List<String>) {
        if (_uiState.value.packs.map { it.id } == packIds) return
        val place = packIds.withIndex().associate { (index, id) -> id to index }
        _uiState.update { state -> state.copy(packs = state.packs.sortedBy { place[it.id] ?: Int.MAX_VALUE }) }
        viewModelScope.launch {
            stickerRepository.reorderPacks(packIds)
                .onFailure { e -> _uiState.update { it.copy(packs = observedPacks, error = AppError.from(e)) } }
        }
    }

    fun openPack(packId: String) =
        _uiState.update { it.copy(openPackId = packId, selectedStickers = emptySet(), selectedPackIds = emptySet()) }

    fun closePack() = _uiState.update { it.copy(openPackId = null, selectedStickers = emptySet()) }

    fun toggleSticker(entry: StickerEntry) = _uiState.update { it.copy(selectedStickers = it.selectedStickers.toggle(entry)) }

    fun clearSelection() = _uiState.update { it.copy(selectedStickers = emptySet()) }

    /** Takes each selected sticker out of the pack it was picked in. One that is then in no pack is deleted from the library. */
    fun removeSelected() = editSelection { byPack ->
        byPack.entries.untilFailure { (packId, ids) -> stickerRepository.removeStickers(packId, ids) }
    }

    /** Moves each selected sticker from the pack it was picked in to [targetPackId]. One picked there stays. */
    fun moveSelectedTo(targetPackId: String) = editSelection { byPack ->
        byPack.entries.filter { it.key != targetPackId }
            .untilFailure { (packId, ids) -> stickerRepository.moveStickers(ids, packId, targetPackId) }
    }

    /**
     * Makes a pack called [name] of the selected stickers, which then leave the
     * packs they were picked in. The new pack comes first, so a sticker is never
     * in no pack, where it would count as deleted.
     */
    fun newPackFromSelected(name: String) = editSelection { byPack ->
        stickerRepository.createPack(name, byPack.values.flatten().distinct()).fold(
            onSuccess = { byPack.entries.untilFailure { (packId, ids) -> stickerRepository.removeStickers(packId, ids) } },
            onFailure = { Result.failure(it) },
        )
    }

    /** Stars every selected sticker that is no favourite yet. They stay in their packs. */
    fun favouriteSelected() {
        val favourites = _uiState.value.packs.firstOrNull { it.kind == StickerPackKind.FAVOURITES }
            ?.stickers.orEmpty().mapTo(HashSet()) { it.id }
        editSelection { byPack ->
            // The toggle would take the star off one that has it.
            byPack.values.flatten().distinct().filterNot { it in favourites }
                .untilFailure { id -> stickerRepository.toggleFavourite(id).map { } }
        }
    }

    /** Deletes the selected stickers from the library: out of every pack, and a WhatsApp import leaves them out. */
    fun deleteSelectedStickers() =
        editSelection { byPack -> stickerRepository.deleteStickers(byPack.values.flatten().distinct()) }

    /** Runs [block] on the selected stickers, as sticker ids by pack id, then clears the selection. */
    private fun editSelection(block: suspend (byPack: Map<String, List<String>>) -> Result<Unit>) {
        val selected = _uiState.value.selectedStickers
        if (selected.isEmpty()) return
        val byPack = selected.groupBy({ it.packId }, { it.stickerId })
        edit { block(byPack) }
        clearSelection()
    }

    fun renamePack(packId: String, name: String) = edit { stickerRepository.renamePack(packId, name) }

    fun deletePack(packId: String) = edit { stickerRepository.deletePack(packId) }

    /** Imports the picked files and archives. Stickers that name no pack go to the `SAVED` pack. */
    fun importFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        import(uris, loosePackName = null, skipKnown = false, folderNewest = null)
    }

    /** Shows the folder the user just granted, with nothing selected: the files that are new since the last import. */
    fun openWhatsAppFolder(treeUri: String) {
        _uiState.update { it.copy(whatsApp = WhatsAppImportState()) }
        viewModelScope.launch {
            val importedUntil = stickerRepository.whatsAppImportedUntil()
            stickerRepository.listWhatsAppFolder(treeUri)
                .onSuccess { files ->
                    // Closed while the listing ran: stay closed.
                    _uiState.update {
                        it.copy(whatsApp = it.whatsApp?.copy(isLoading = false, files = files, importedUntil = importedUntil))
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(whatsApp = null, error = AppError.from(e)) } }
        }
    }

    fun closeWhatsApp() = _uiState.update { it.copy(whatsApp = null) }

    fun toggleWhatsAppFile(uri: String) =
        _uiState.update { it.copy(whatsApp = it.whatsApp?.let { wa -> wa.copy(selected = wa.selected.toggle(uri)) }) }

    /** Shows every file of the folder, or only the new ones again. A selected file that is hidden leaves the selection. */
    fun setShowAllWhatsApp(showAll: Boolean) = _uiState.update { state ->
        state.copy(
            whatsApp = state.whatsApp?.let { wa ->
                val next = wa.copy(showAll = showAll)
                val shown = next.shownFiles.mapTo(HashSet()) { it.uri }
                next.copy(selected = next.selected.filterTo(LinkedHashSet()) { it in shown })
            }
        )
    }

    /** Selects every file that is shown, or none of them when all are already selected. */
    fun toggleSelectAllWhatsApp() = _uiState.update { state ->
        state.copy(
            whatsApp = state.whatsApp?.let { wa ->
                val shown = wa.shownFiles.mapTo(HashSet()) { it.uri }
                wa.copy(selected = if (wa.allSelected) wa.selected - shown else wa.selected + shown)
            }
        )
    }

    fun importSelectedWhatsApp() {
        val wa = _uiState.value.whatsApp ?: return
        // In the folder's order, which is newest first.
        val uris = wa.files.map { it.uri }.filter { it in wa.selected }
        if (uris.isEmpty()) return
        // The folder holds every sticker ever seen, so what the library knows is left where it is.
        import(uris, loosePackName = WHATSAPP_PACK_NAME, skipKnown = true, folderNewest = wa.files.maxOf { it.lastModified })
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    fun clearNotice() = _uiState.update { it.copy(notice = null) }

    /**
     * A finished import closes the WhatsApp view, the only place one can start from while it is open.
     *
     * [folderNewest] is the newest `lastModified` of the WhatsApp folder as it
     * was listed. An import that succeeds moves the mark of what is new there.
     * A failed import does not, and neither does closing the view.
     */
    private fun import(uris: List<String>, loosePackName: String?, skipKnown: Boolean, folderNewest: Long?) {
        if (_uiState.value.isImporting) return
        _uiState.update { it.copy(isImporting = true) }
        viewModelScope.launch {
            stickerRepository.importFrom(uris, loosePackName, skipKnown)
                .onSuccess { result ->
                    folderNewest?.let { stickerRepository.markWhatsAppImported(it) }
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

    private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

    /** The entries whose pack still holds their sticker. */
    private fun Set<StickerEntry>.heldBy(packs: List<StickerPack>): Set<StickerEntry> {
        if (isEmpty()) return this
        val held = packs.associate { pack -> pack.id to pack.stickers.mapTo(HashSet()) { it.id } }
        return filterTo(LinkedHashSet()) { held[it.packId]?.contains(it.stickerId) == true }
    }

    /** Runs [step] on each item in turn. Stops at the first failure and returns it. */
    private suspend fun <T> Iterable<T>.untilFailure(step: suspend (T) -> Result<Unit>): Result<Unit> {
        for (item in this) step(item).onFailure { return Result.failure(it) }
        return Result.success(Unit)
    }

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
