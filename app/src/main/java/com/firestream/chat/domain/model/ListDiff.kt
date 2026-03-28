package com.firestream.chat.domain.model

data class ListDiff(
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val checked: List<String> = emptyList(),
    val unchecked: List<String> = emptyList(),
    val edited: List<String> = emptyList(),
    val titleChanged: String? = null,
    val deleted: Boolean = false
) {
    val isEmpty: Boolean
        get() = !deleted && added.isEmpty() && removed.isEmpty() && checked.isEmpty() &&
                unchecked.isEmpty() && edited.isEmpty() && titleChanged == null

    fun toSummaryString(): String {
        if (deleted) return "list deleted"
        val parts = mutableListOf<String>()
        if (added.isNotEmpty()) parts.add("+${added.size} added")
        if (removed.isNotEmpty()) parts.add("-${removed.size} removed")
        if (checked.isNotEmpty()) parts.add("${checked.size} checked")
        if (unchecked.isNotEmpty()) parts.add("${unchecked.size} unchecked")
        if (edited.isNotEmpty()) parts.add("${edited.size} edited")
        if (titleChanged != null) parts.add("title changed")
        return parts.joinToString(", ").ifEmpty { "no changes" }
    }

    fun toMap(): Map<String, Any?> = buildMap {
        if (added.isNotEmpty()) put("added", added)
        if (removed.isNotEmpty()) put("removed", removed)
        if (checked.isNotEmpty()) put("checked", checked)
        if (unchecked.isNotEmpty()) put("unchecked", unchecked)
        if (edited.isNotEmpty()) put("edited", edited)
        if (titleChanged != null) put("titleChanged", titleChanged)
        if (deleted) put("deleted", true)
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): ListDiff = ListDiff(
            added = (map["added"] as? List<String>) ?: emptyList(),
            removed = (map["removed"] as? List<String>) ?: emptyList(),
            checked = (map["checked"] as? List<String>) ?: emptyList(),
            unchecked = (map["unchecked"] as? List<String>) ?: emptyList(),
            edited = (map["edited"] as? List<String>) ?: emptyList(),
            titleChanged = map["titleChanged"] as? String,
            deleted = map["deleted"] as? Boolean ?: false
        )

        fun accumulate(current: ListDiff, new: ListDiff): ListDiff {
            val addedSet = current.added.toMutableList()
            val removedSet = current.removed.toMutableList()
            val checkedSet = current.checked.toMutableList()
            val uncheckedSet = current.unchecked.toMutableList()

            // Adding then removing same item cancels out
            for (item in new.added) {
                if (item in removedSet) removedSet.remove(item) else addedSet.add(item)
            }
            for (item in new.removed) {
                if (item in addedSet) addedSet.remove(item) else removedSet.add(item)
            }

            // Check/uncheck same item cancels out
            for (item in new.checked) {
                if (item in uncheckedSet) uncheckedSet.remove(item) else checkedSet.add(item)
            }
            for (item in new.unchecked) {
                if (item in checkedSet) checkedSet.remove(item) else uncheckedSet.add(item)
            }

            val editedSet = current.edited.toMutableList()
            editedSet.addAll(new.edited)

            return ListDiff(
                added = addedSet,
                removed = removedSet,
                checked = checkedSet,
                unchecked = uncheckedSet,
                edited = editedSet,
                titleChanged = new.titleChanged ?: current.titleChanged
            )
        }
    }
}
