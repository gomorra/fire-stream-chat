package com.firestream.chat.domain.model

enum class ListType {
    CHECKLIST,
    SHOPPING,
    GENERIC;

    fun displayName(): String = when (this) {
        CHECKLIST -> "Checklist"
        SHOPPING -> "Shopping List"
        GENERIC -> "Generic List"
    }
}

enum class GenericListStyle {
    BULLET, NUMBER, DASH, NONE;

    fun displayName(): String = when (this) {
        BULLET -> "• Bullet"
        NUMBER -> "1. Number"
        DASH -> "– Dash"
        NONE -> "None"
    }
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
    // Denormalized counts mirrored on the metadata doc so the Lists tab
    // can show "N items, M checked" without loading every list's items.
    // Maintained via FieldValue.increment in the same batch as the
    // corresponding subcollection write.
    val itemCount: Int = 0,
    val checkedCount: Int = 0,
    val sharedChatIds: List<String> = emptyList(),
    val genericStyle: GenericListStyle = GenericListStyle.BULLET
)
