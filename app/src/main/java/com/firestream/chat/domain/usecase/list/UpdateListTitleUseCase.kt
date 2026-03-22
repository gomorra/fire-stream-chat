package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.repository.ListRepository
import javax.inject.Inject

class UpdateListTitleUseCase @Inject constructor(
    private val listRepository: ListRepository
) {
    suspend operator fun invoke(listId: String, title: String): Result<Unit> =
        listRepository.updateListTitle(listId, title)
}
