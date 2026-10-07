package com.firestream.chat.ui.chat.picker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Which tab [PickerPanel] shows, whether its search is open, and the query.
 *
 * A host that only mounts the panel lets the panel remember its own state. A
 * host that lays the panel out differently while a search runs hoists it, so it
 * can read [searchingTab] in the same frame the search opens. The composer does
 * this: emoji search sits in a strip above the keyboard and sticker search fills
 * the screen. The state outlives the move between those places, so the query
 * and the open field are not lost.
 *
 * The query belongs to the active tab. Switching tabs clears it, because a query
 * typed for one tab means nothing on another.
 */
@Stable
internal class PickerPanelState(
    initialTab: PickerTab,
    initialSearchOpen: Boolean = false,
    initialQuery: String = "",
) {
    var activeTab by mutableStateOf(initialTab)
        private set

    var searchOpen by mutableStateOf(initialSearchOpen)
        private set

    var query by mutableStateOf(initialQuery)

    /** The tab being searched, or null while the search is closed. */
    val searchingTab: PickerTab?
        get() = if (searchOpen) activeTab else null

    fun selectTab(tab: PickerTab) {
        if (tab == activeTab) return
        activeTab = tab
        query = ""
    }

    fun openSearch() {
        searchOpen = true
    }

    /** Clears the query as well: a filter running behind a hidden field is a tab that looks broken. */
    fun closeSearch() {
        query = ""
        searchOpen = false
    }

    /** Back to how a fresh panel opens: on [tab], search closed. */
    fun reset(tab: PickerTab) {
        closeSearch()
        activeTab = tab
    }

    companion object {
        val Saver: Saver<PickerPanelState, Any> = Saver(
            save = { listOf(it.activeTab.name, it.searchOpen, it.query) },
            restore = {
                val (tab, open, query) = it as List<*>
                PickerPanelState(PickerTab.valueOf(tab as String), open as Boolean, query as String)
            },
        )
    }
}

/** A [PickerPanelState] that opens on the first of [tabs] and survives process death. */
@Composable
internal fun rememberPickerPanelState(tabs: List<PickerTab>): PickerPanelState =
    rememberSaveable(tabs, saver = PickerPanelState.Saver) {
        PickerPanelState(tabs.firstOrNull() ?: PickerTab.EMOJI)
    }
