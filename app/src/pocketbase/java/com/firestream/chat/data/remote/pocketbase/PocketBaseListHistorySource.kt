package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.ListHistorySource
import com.firestream.chat.domain.model.ListHistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Step 4 stub. Stays a stub through v0; list history is out of scope. */
@Singleton
class PocketBaseListHistorySource @Inject constructor() : ListHistorySource {
    override suspend fun addEntry(listId: String, entry: ListHistoryEntry) {
        // No-op stub — list-history writes are best-effort and the parent
        // ListSource is also stubbed.
    }

    override fun observeHistory(listId: String): Flow<List<ListHistoryEntry>> = emptyFlow()
}
