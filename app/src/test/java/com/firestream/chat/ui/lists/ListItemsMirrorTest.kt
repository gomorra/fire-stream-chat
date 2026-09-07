package com.firestream.chat.ui.lists

import com.firestream.chat.domain.model.ListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListItemsMirrorTest {

    private fun item(id: String, checked: Boolean = false, text: String = id) =
        ListItem(id = id, text = text, isChecked = checked)

    @Test
    fun `no pending reorder adopts the observed list`() {
        val observed = listOf(item("a"), item("b"))

        val mirror = reconcileListItems(observed, pendingOrderIds = null)

        assertEquals(observed, mirror.items)
        assertNull(mirror.pendingOrderIds)
    }

    @Test
    fun `clear checked applies while a reorder is still in flight`() {
        // Regression: the mirror used to swallow updates that arrived while a reorder
        // echo was pending, so "Clear checked" only showed up after leaving the screen.
        val observed = listOf(item("a"), item("b"))

        val mirror = reconcileListItems(observed, pendingOrderIds = listOf("b", "a", "c"))

        assertEquals(listOf("a", "b"), mirror.items.map { it.id })
        assertNull(mirror.pendingOrderIds)
    }

    @Test
    fun `added item applies while a reorder is still in flight`() {
        val observed = listOf(item("a"), item("b"), item("c"))

        val mirror = reconcileListItems(observed, pendingOrderIds = listOf("b", "a"))

        assertEquals(listOf("a", "b", "c"), mirror.items.map { it.id })
        assertNull(mirror.pendingOrderIds)
    }

    @Test
    fun `pending reorder keeps the dragged order until the echo matches`() {
        val observed = listOf(item("a"), item("b"), item("c"))

        val mirror = reconcileListItems(observed, pendingOrderIds = listOf("c", "a", "b"))

        assertEquals(listOf("c", "a", "b"), mirror.items.map { it.id })
        assertEquals(listOf("c", "a", "b"), mirror.pendingOrderIds)
    }

    @Test
    fun `pending reorder still adopts field changes on the same items`() {
        val observed = listOf(item("a"), item("b", checked = true), item("c"))

        val mirror = reconcileListItems(observed, pendingOrderIds = listOf("c", "a", "b"))

        assertEquals(listOf("c", "a", "b"), mirror.items.map { it.id })
        assertEquals(true, mirror.items.single { it.id == "b" }.isChecked)
    }

    @Test
    fun `matching echo clears the pending order`() {
        val observed = listOf(item("c"), item("a"), item("b"))

        val mirror = reconcileListItems(observed, pendingOrderIds = listOf("c", "a", "b"))

        assertEquals(observed, mirror.items)
        assertNull(mirror.pendingOrderIds)
    }
}
