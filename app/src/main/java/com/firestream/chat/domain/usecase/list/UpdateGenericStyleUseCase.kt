package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.model.GenericListStyle
import com.firestream.chat.domain.repository.ListRepository
import javax.inject.Inject

class UpdateGenericStyleUseCase @Inject constructor(
    private val listRepository: ListRepository
) {
    suspend operator fun invoke(listId: String, style: GenericListStyle): Result<Unit> =
        listRepository.updateGenericStyle(listId, style)
}
