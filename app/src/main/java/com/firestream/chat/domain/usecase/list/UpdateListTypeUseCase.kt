package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.repository.ListRepository
import javax.inject.Inject

class UpdateListTypeUseCase @Inject constructor(
    private val listRepository: ListRepository
) {
    suspend operator fun invoke(listId: String, type: ListType): Result<Unit> =
        listRepository.updateListType(listId, type)
}
