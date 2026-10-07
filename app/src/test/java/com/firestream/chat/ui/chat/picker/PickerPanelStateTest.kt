package com.firestream.chat.ui.chat.picker

import com.firestream.chat.ui.chat.ComposerSearchLayout
import com.firestream.chat.ui.chat.composerSearchLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The picker's hoisted state, and where the composer puts the picker while a search runs. */
class PickerPanelStateTest {

    @Test
    fun `the search reports its tab only while it is open`() {
        val state = PickerPanelState(PickerTab.EMOJI)
        assertNull(state.searchingTab)

        state.openSearch()
        assertEquals(PickerTab.EMOJI, state.searchingTab)

        state.closeSearch()
        assertNull(state.searchingTab)
    }

    @Test
    fun `closing the search clears the query`() {
        val state = PickerPanelState(PickerTab.EMOJI)
        state.openSearch()
        state.query = "cat"

        state.closeSearch()

        assertEquals("", state.query)
    }

    @Test
    fun `switching tabs clears the query, and selecting the active tab keeps it`() {
        val state = PickerPanelState(PickerTab.EMOJI)
        state.query = "cat"

        state.selectTab(PickerTab.EMOJI)
        assertEquals("cat", state.query)

        state.selectTab(PickerTab.STICKER_LIBRARY)
        assertEquals("", state.query)
        assertEquals(PickerTab.STICKER_LIBRARY, state.activeTab)
    }

    @Test
    fun `reset returns to the first tab with the search closed`() {
        val state = PickerPanelState(PickerTab.EMOJI)
        state.selectTab(PickerTab.STICKER_LIBRARY)
        state.openSearch()
        state.query = "dog"

        state.reset(PickerTab.EMOJI)

        assertEquals(PickerTab.EMOJI, state.activeTab)
        assertNull(state.searchingTab)
        assertEquals("", state.query)
    }

    @Test
    fun `emoji search is a strip on the keyboard, and the panel when the keyboard is down`() {
        assertEquals(ComposerSearchLayout.STRIP, composerSearchLayout(PickerTab.EMOJI, imeVisible = true))
        assertEquals(ComposerSearchLayout.PANEL, composerSearchLayout(PickerTab.EMOJI, imeVisible = false))
    }

    @Test
    fun `sticker and GIF search fill the screen whether or not the keyboard is up`() {
        for (ime in listOf(true, false)) {
            assertEquals(ComposerSearchLayout.FULL, composerSearchLayout(PickerTab.STICKER_LIBRARY, ime))
            assertEquals(ComposerSearchLayout.FULL, composerSearchLayout(PickerTab.GIF, ime))
        }
    }

    @Test
    fun `no search leaves the picker in the keyboard's place`() {
        assertEquals(ComposerSearchLayout.PANEL, composerSearchLayout(null, imeVisible = true))
        assertEquals(ComposerSearchLayout.PANEL, composerSearchLayout(null, imeVisible = false))
    }
}
