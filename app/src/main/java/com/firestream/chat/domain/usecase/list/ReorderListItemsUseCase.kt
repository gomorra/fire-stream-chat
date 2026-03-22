package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.repository.ListRepository
import javax.inject.Inject

class ReorderListItemsUseCase @Inject constructor(
    private val listRepository: ListRepository
) {
    suspend operator fun invoke(listId: String, items: List<ListItem>): Result<Unit> =
        listRepository.reorderItems(listId, items)
}
