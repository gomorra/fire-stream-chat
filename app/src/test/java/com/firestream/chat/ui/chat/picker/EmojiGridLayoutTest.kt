package com.firestream.chat.ui.chat.picker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the long-press size panel goes, and which cells count as a row.
 *
 * The picker's grid is one flat list in which a category header is a single
 * item spanning the full row, and a category's emojis run straight on from the
 * last one without padding out the row. So `index % 8` is not a column and
 * `index / 8` is not a row: the first row of Recents already starts at index 1,
 * and every header below shifts the arithmetic by another seven. The size panel
 * used that arithmetic to decide which side of the held cell to sit on, so for
 * a cell in the last column it usually chose the right, and drew itself off the
 * edge of the screen — the bar and the percentage were simply not there.
 */
class EmojiGridLayoutTest {

    private val columns = 8

    private fun header() = columns
    private fun cell() = 1

    // ── layoutGridCells ─────────────────────────────────────────────────────

    @Test
    fun `the last cell of the first row after a header is in the last column`() {
        // [header, 8 emojis] — index 8 is the eighth emoji, the right-most cell.
        val spans = listOf(header()) + List(8) { cell() }

        val cells = layoutGridCells(spans, columns)

        assertEquals(GridCell(row = 1, column = 7), cells[8])
        assertEquals(GridCell(row = 1, column = 0), cells[1])
    }

    @Test
    fun `a full-span header occupies a row of its own`() {
        val spans = listOf(header(), cell(), cell())

        val cells = layoutGridCells(spans, columns)

        assertEquals(GridCell(row = 0, column = 0), cells[0])
        assertEquals(GridCell(row = 1, column = 0), cells[1])
        assertEquals(GridCell(row = 1, column = 1), cells[2])
    }

    @Test
    fun `a header after a part-filled row starts on the next row and its emojis on the one after`() {
        // A category of three emojis, then the next category — no padding in between.
        val spans = listOf(header(), cell(), cell(), cell(), header(), cell(), cell())

        val cells = layoutGridCells(spans, columns)

        assertEquals(GridCell(row = 2, column = 0), cells[4])
        assertEquals(GridCell(row = 3, column = 0), cells[5])
        assertEquals(GridCell(row = 3, column = 1), cells[6])
    }

    @Test
    fun `a header directly after a full row does not leave an empty row behind`() {
        val spans = listOf(header()) + List(8) { cell() } + listOf(header(), cell())

        val cells = layoutGridCells(spans, columns)

        assertEquals(GridCell(row = 2, column = 0), cells[9])
        assertEquals(GridCell(row = 3, column = 0), cells[10])
    }

    @Test
    fun `cells wrap at the column count`() {
        val spans = List(17) { cell() }

        val cells = layoutGridCells(spans, columns)

        assertEquals(GridCell(row = 0, column = 7), cells[7])
        assertEquals(GridCell(row = 1, column = 0), cells[8])
        assertEquals(GridCell(row = 2, column = 0), cells[16])
    }

    @Test
    fun `every item gets a cell`() {
        val spans = listOf(header(), cell(), cell(), header(), cell())

        assertEquals(spans.size, layoutGridCells(spans, columns).size)
    }

    // ── sizePickerPanelX ────────────────────────────────────────────────────

    @Test
    fun `the panel sits right of the cell when it fits`() {
        val x = sizePickerPanelX(cellLeft = 100, cellRight = 140, panelWidth = 60, gridWidth = 360, gap = 8)

        assertEquals(148, x)
    }

    @Test
    fun `the panel flips to the left of the cell when it would overrun the grid`() {
        // Right-most cell of a 360px grid: 148 + 60 would end at 208, fine — but 320 + 8 + 60 would not.
        val x = sizePickerPanelX(cellLeft = 320, cellRight = 352, panelWidth = 60, gridWidth = 360, gap = 8)

        assertEquals(320 - 8 - 60, x)
    }

    @Test
    fun `the panel flips left when it fits exactly nowhere on the right`() {
        val x = sizePickerPanelX(cellLeft = 300, cellRight = 340, panelWidth = 20, gridWidth = 360, gap = 8)

        // 340 + 8 + 20 = 368 > 360 → left.
        assertEquals(300 - 8 - 20, x)
    }

    @Test
    fun `the panel is never placed past the left edge of the grid`() {
        val x = sizePickerPanelX(cellLeft = 10, cellRight = 50, panelWidth = 400, gridWidth = 360, gap = 8)

        assertEquals(0, x)
    }
}
