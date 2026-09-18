package com.firestream.chat.ui.chat.picker

/** A position in a [androidx.compose.foundation.lazy.grid.LazyVerticalGrid], counted from zero. */
internal data class GridCell(val row: Int, val column: Int)

/**
 * The row and column of every item in a [columns]-wide lazy grid, given each
 * item's span.
 *
 * Mirrors how the grid itself lays items out: an item that does not fit in what
 * is left of the current row starts the next one. The emoji grid needs this
 * because its headers are single items spanning the whole row and its
 * categories do not pad out their last row, so an item's index says nothing
 * about where it is on screen — `index % columns` is not its column.
 */
internal fun layoutGridCells(spans: List<Int>, columns: Int): List<GridCell> {
    require(columns > 0) { "columns must be positive, was $columns" }
    var row = 0
    var column = 0
    return spans.map { span ->
        val width = span.coerceIn(1, columns)
        if (column + width > columns) {
            row++
            column = 0
        }
        val cell = GridCell(row, column)
        column += width
        cell
    }
}

/**
 * Where the size panel sits horizontally, in the grid's own pixels.
 *
 * To the right of the held cell when the whole panel fits inside the grid,
 * otherwise to its left — and never past the grid's left edge, which is the
 * one place it can still overlap the cell rather than vanish off screen.
 */
internal fun sizePickerPanelX(
    cellLeft: Int,
    cellRight: Int,
    panelWidth: Int,
    gridWidth: Int,
    gap: Int,
): Int {
    val right = cellRight + gap
    return if (right + panelWidth <= gridWidth) right
    else (cellLeft - gap - panelWidth).coerceAtLeast(0)
}
