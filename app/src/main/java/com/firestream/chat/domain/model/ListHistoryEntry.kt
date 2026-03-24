package com.firestream.chat.domain.model

data class ListHistoryEntry(
    val id: String = "",
    val action: HistoryAction = HistoryAction.CREATED,
    val itemId: String? = null,
    val itemText: String? = null,
    val userId: String = "",
    val userName: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
