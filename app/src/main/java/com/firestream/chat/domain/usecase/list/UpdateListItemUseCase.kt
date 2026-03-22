package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.repository.ListRepository
import javax.inject.Inject

class UpdateListItemUseCase @Inject constructor(
    private val listRepository: ListRepository
) {
    suspend operator fun invoke(listId: String, itemId: String, text: String, quantity: String? = null, unit: String? = null): Result<Unit> =
        listRepository.updateItem(listId, itemId, text, quantity, unit)
}
