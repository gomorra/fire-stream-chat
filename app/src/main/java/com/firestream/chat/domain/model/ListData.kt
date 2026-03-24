package com.firestream.chat.domain.model

enum class ListType {
    CHECKLIST,
    SHOPPING,
    GENERIC
}

data class ListItem(
    val id: String = "",
    val text: String = "",
    val isChecked: Boolean = false,
    val quantity: String? = null,
    val unit: String? = null,
    val order: Int = 0,
    val addedBy: String = ""
)

data class ListData(
    val id: String = "",
    val title: String = "",
    val type: ListType = ListType.CHECKLIST,
    val createdBy: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val participants: List<String> = emptyList(),
    val items: List<ListItem> = emptyList(),
    val sharedChatIds: List<String> = emptyList()
)
