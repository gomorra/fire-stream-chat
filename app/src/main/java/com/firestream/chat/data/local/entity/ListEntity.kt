package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.model.ListType
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "lists")
data class ListEntity(
    @PrimaryKey val id: String,
    val title: String,
    val type: String,
    val createdBy: String,
    val createdAt: Long,
    val updatedAt: Long,
    val participants: String, // JSON array of userIds
    val items: String // JSON array of ListItem
) {
    fun toDomain() = ListData(
        id = id,
        title = title,
        type = runCatching { ListType.valueOf(type) }.getOrDefault(ListType.CHECKLIST),
        createdBy = createdBy,
        createdAt = createdAt,
        updatedAt = updatedAt,
        participants = parseStringList(participants),
        items = parseItemsJson(items)
    )

    companion object {
        fun fromDomain(list: ListData) = ListEntity(
            id = list.id,
            title = list.title,
            type = list.type.name,
            createdBy = list.createdBy,
            createdAt = list.createdAt,
            updatedAt = list.updatedAt,
            participants = JSONArray(list.participants).toString(),
            items = itemsToJson(list.items)
        )

        fun itemsToJson(items: List<ListItem>): String {
            return JSONArray().apply {
                items.forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.id)
                        put("text", item.text)
                        put("isChecked", item.isChecked)
                        put("quantity", item.quantity)
                        put("unit", item.unit)
                        put("order", item.order)
                        put("addedBy", item.addedBy)
                    })
                }
            }.toString()
        }

        fun parseItemsJson(json: String): List<ListItem> {
            return try {
                val arr = JSONArray(json)
                List(arr.length()) { i ->
                    val obj = arr.getJSONObject(i)
                    ListItem(
                        id = obj.getString("id"),
                        text = obj.getString("text"),
                        isChecked = obj.optBoolean("isChecked", false),
                        quantity = obj.optString("quantity", null).takeIf { it != "null" },
                        unit = obj.optString("unit", null).takeIf { it != "null" },
                        order = obj.optInt("order", 0),
                        addedBy = obj.optString("addedBy", "")
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun parseStringList(json: String): List<String> {
            return try {
                val arr = JSONArray(json)
                List(arr.length()) { i -> arr.getString(i) }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
