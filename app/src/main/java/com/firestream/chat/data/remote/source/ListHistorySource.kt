package com.firestream.chat.data.remote.source

import com.firestream.chat.domain.model.ListHistoryEntry
import kotlinx.coroutines.flow.Flow

/** Backend-neutral list-audit-trail boundary. Stub on pocketbase flavor in v0. */
interface ListHistorySource {
    suspend fun addEntry(listId: String, entry: ListHistoryEntry)
    fun observeHistory(listId: String): Flow<List<ListHistoryEntry>>
}
