package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.repository.ListRepository
import javax.inject.Inject

class DeleteListUseCase @Inject constructor(
    private val listRepository: ListRepository
) {
    suspend operator fun invoke(listId: String): Result<Unit> =
        listRepository.deleteList(listId)
}
