package com.firestream.chat.data.local.entity

import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.model.ListType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListSerializationTest {

    @Test
    fun `items round-trip through JSON`() {
        val items = listOf(
            ListItem(id = "1", text = "Milk", isChecked = true, quantity = "2", unit = "L", order = 0, addedBy = "user1"),
            ListItem(id = "2", text = "Bread", isChecked = false, order = 1, addedBy = "user2")
        )

        val json = ListEntity.itemsToJson(items)
        val parsed = ListEntity.parseItemsJson(json)

        assertEquals(2, parsed.size)
        assertEquals("Milk", parsed[0].text)
        assertEquals(true, parsed[0].isChecked)
        assertEquals("2", parsed[0].quantity)
        assertEquals("L", parsed[0].unit)
        assertEquals("Bread", parsed[1].text)
        assertEquals(false, parsed[1].isChecked)
        assertNull(parsed[1].quantity)
        assertNull(parsed[1].unit)
    }

    @Test
    fun `empty items round-trip`() {
        val json = ListEntity.itemsToJson(emptyList())
        val parsed = ListEntity.parseItemsJson(json)
        assertEquals(0, parsed.size)
    }

    @Test
    fun `ListEntity to domain round-trip`() {
        val listData = ListData(
            id = "list1",
            title = "Shopping",
            type = ListType.SHOPPING,
            createdBy = "user1",
            createdAt = 1000L,
            updatedAt = 2000L,
            participants = listOf("user1", "user2"),
            items = listOf(
                ListItem(id = "1", text = "Eggs", isChecked = false, quantity = "12", unit = "pcs", order = 0, addedBy = "user1")
            )
        )

        val entity = ListEntity.fromDomain(listData)
        val restored = entity.toDomain()

        assertEquals(listData.id, restored.id)
        assertEquals(listData.title, restored.title)
        assertEquals(listData.type, restored.type)
        assertEquals(listData.createdBy, restored.createdBy)
        assertEquals(listData.participants, restored.participants)
        assertEquals(1, restored.items.size)
        assertEquals("Eggs", restored.items[0].text)
        assertEquals("12", restored.items[0].quantity)
    }

    @Test
    fun `invalid JSON returns empty list`() {
        val parsed = ListEntity.parseItemsJson("not json")
        assertEquals(0, parsed.size)
    }
}
