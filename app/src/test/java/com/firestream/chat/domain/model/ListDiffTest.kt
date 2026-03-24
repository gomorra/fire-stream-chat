package com.firestream.chat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListDiffTest {

    @Test
    fun `isEmpty is true for default instance`() {
        assertTrue(ListDiff().isEmpty)
    }

    @Test
    fun `isEmpty is false when added is non-empty`() {
        assertFalse(ListDiff(added = listOf("Milk")).isEmpty)
    }

    @Test
    fun `isEmpty is false when reordered is true`() {
        assertFalse(ListDiff(reordered = true).isEmpty)
    }

    // --- toSummaryString ---

    @Test
    fun `toSummaryString returns no changes for empty diff`() {
        assertEquals("no changes", ListDiff().toSummaryString())
    }

    @Test
    fun `toSummaryString includes all parts`() {
        val diff = ListDiff(
            added = listOf("A", "B"),
            removed = listOf("C"),
            checked = listOf("D"),
            unchecked = listOf("E", "F"),
            titleChanged = "New Title",
            reordered = true
        )
        val summary = diff.toSummaryString()
        assertTrue(summary.contains("+2 added"))
        assertTrue(summary.contains("-1 removed"))
        assertTrue(summary.contains("1 checked"))
        assertTrue(summary.contains("2 unchecked"))
        assertTrue(summary.contains("title changed"))
        assertTrue(summary.contains("reordered"))
    }

    // --- toMap / fromMap round-trip ---

    @Test
    fun `toMap and fromMap round-trip preserves all fields`() {
        val diff = ListDiff(
            added = listOf("Milk", "Eggs"),
            removed = listOf("Bread"),
            checked = listOf("Butter"),
            unchecked = listOf("Cheese"),
            titleChanged = "Updated Title",
            reordered = true
        )

        val map = diff.toMap()
        val restored = ListDiff.fromMap(map)

        assertEquals(diff.added, restored.added)
        assertEquals(diff.removed, restored.removed)
        assertEquals(diff.checked, restored.checked)
        assertEquals(diff.unchecked, restored.unchecked)
        assertEquals(diff.titleChanged, restored.titleChanged)
        assertEquals(diff.reordered, restored.reordered)
    }

    @Test
    fun `toMap omits empty lists and false reordered`() {
        val diff = ListDiff(added = listOf("Milk"))
        val map = diff.toMap()

        assertTrue(map.containsKey("added"))
        assertFalse(map.containsKey("removed"))
        assertFalse(map.containsKey("checked"))
        assertFalse(map.containsKey("unchecked"))
        assertFalse(map.containsKey("titleChanged"))
        assertFalse(map.containsKey("reordered"))
    }

    @Test
    fun `fromMap defaults missing fields to empty`() {
        val restored = ListDiff.fromMap(emptyMap())

        assertTrue(restored.added.isEmpty())
        assertTrue(restored.removed.isEmpty())
        assertNull(restored.titleChanged)
        assertFalse(restored.reordered)
        assertTrue(restored.isEmpty)
    }

    // --- accumulate ---

    @Test
    fun `accumulate - adding then removing same item cancels out`() {
        val base = ListDiff(added = listOf("Milk"))
        val update = ListDiff(removed = listOf("Milk"))

        val result = ListDiff.accumulate(base, update)

        assertTrue(result.added.isEmpty())
        assertTrue(result.removed.isEmpty())
    }

    @Test
    fun `accumulate - removing then adding same item cancels out`() {
        val base = ListDiff(removed = listOf("Eggs"))
        val update = ListDiff(added = listOf("Eggs"))

        val result = ListDiff.accumulate(base, update)

        assertTrue(result.added.isEmpty())
        assertTrue(result.removed.isEmpty())
    }

    @Test
    fun `accumulate - check then uncheck same item cancels out`() {
        val base = ListDiff(checked = listOf("Milk"))
        val update = ListDiff(unchecked = listOf("Milk"))

        val result = ListDiff.accumulate(base, update)

        assertTrue(result.checked.isEmpty())
        assertTrue(result.unchecked.isEmpty())
    }

    @Test
    fun `accumulate - uncheck then check same item cancels out`() {
        val base = ListDiff(unchecked = listOf("Milk"))
        val update = ListDiff(checked = listOf("Milk"))

        val result = ListDiff.accumulate(base, update)

        assertTrue(result.checked.isEmpty())
        assertTrue(result.unchecked.isEmpty())
    }

    @Test
    fun `accumulate - titleChanged overwrites previous`() {
        val base = ListDiff(titleChanged = "Old Title")
        val update = ListDiff(titleChanged = "New Title")

        val result = ListDiff.accumulate(base, update)

        assertEquals("New Title", result.titleChanged)
    }

    @Test
    fun `accumulate - keeps current titleChanged when update has none`() {
        val base = ListDiff(titleChanged = "My Title")
        val update = ListDiff(added = listOf("Item"))

        val result = ListDiff.accumulate(base, update)

        assertEquals("My Title", result.titleChanged)
    }

    @Test
    fun `accumulate - reordered is sticky once true`() {
        val base = ListDiff(reordered = true)
        val update = ListDiff(added = listOf("Item"))

        val result = ListDiff.accumulate(base, update)

        assertTrue(result.reordered)
    }

    @Test
    fun `accumulate - distinct items accumulate independently`() {
        val base = ListDiff(added = listOf("Milk"))
        val update = ListDiff(added = listOf("Eggs"))

        val result = ListDiff.accumulate(base, update)

        assertEquals(listOf("Milk", "Eggs"), result.added)
    }
}
