package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.repository.ListRepository
import javax.inject.Inject

class AddListItemUseCase @Inject constructor(
    private val listRepository: ListRepository
) {
    suspend operator fun invoke(listId: String, text: String, quantity: String? = null, unit: String? = null): Result<Unit> =
        listRepository.addItem(listId, text, quantity, unit)
}
