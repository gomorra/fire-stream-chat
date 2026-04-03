package com.firestream.chat.data.local.entity

import com.firestream.chat.domain.model.ListDiff
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageEntityListDiffRoundTripTest {

    private fun roundTrip(diff: ListDiff): ListDiff {
        val message = Message(
            id = "msg1",
            chatId = "chat1",
            senderId = "user1",
            content = "📋 List: Groceries",
            type = MessageType.LIST,
            listId = "list1",
            listDiff = diff
        )
        val entity = MessageEntity.fromDomain(message)
        val restored = entity.toDomain()
        return restored.listDiff!!
    }

    @Test
    fun `shared flag survives Room round-trip`() {
        val restored = roundTrip(ListDiff(shared = true))
        assertTrue(restored.shared)
    }

    @Test
    fun `unshared flag survives Room round-trip`() {
        val restored = roundTrip(ListDiff(unshared = true))
        assertTrue(restored.unshared)
    }

    @Test
    fun `deleted flag survives Room round-trip`() {
        val restored = roundTrip(ListDiff(deleted = true))
        assertTrue(restored.deleted)
    }

    @Test
    fun `all ListDiff fields survive Room round-trip`() {
        val original = ListDiff(
            added = listOf("Milk", "Eggs"),
            removed = listOf("Bread"),
            checked = listOf("Butter"),
            unchecked = listOf("Cheese"),
            edited = listOf("Sugar"),
            titleChanged = "Updated Groceries",
            deleted = false,
            unshared = true,
            shared = false
        )
        val restored = roundTrip(original)

        assertEquals(original.added, restored.added)
        assertEquals(original.removed, restored.removed)
        assertEquals(original.checked, restored.checked)
        assertEquals(original.unchecked, restored.unchecked)
        assertEquals(original.edited, restored.edited)
        assertEquals(original.titleChanged, restored.titleChanged)
        assertEquals(original.deleted, restored.deleted)
        assertEquals(original.unshared, restored.unshared)
        assertEquals(original.shared, restored.shared)
    }

    @Test
    fun `null listDiff survives Room round-trip`() {
        val message = Message(
            id = "msg1",
            chatId = "chat1",
            senderId = "user1",
            type = MessageType.LIST,
            listId = "list1",
            listDiff = null
        )
        val entity = MessageEntity.fromDomain(message)
        val restored = entity.toDomain()
        assertEquals(null, restored.listDiff)
    }
}
