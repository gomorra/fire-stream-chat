package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.model.ListHistoryEntry
import com.firestream.chat.domain.repository.ListRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveListHistoryUseCase @Inject constructor(
    private val listRepository: ListRepository
) {
    operator fun invoke(listId: String): Flow<List<ListHistoryEntry>> =
        listRepository.observeHistory(listId)
}
