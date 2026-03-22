package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.repository.ListRepository
import javax.inject.Inject

class ToggleListItemUseCase @Inject constructor(
    private val listRepository: ListRepository
) {
    suspend operator fun invoke(listId: String, itemId: String): Result<Unit> =
        listRepository.toggleItemChecked(listId, itemId)
}
