package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.model.GenericListStyle
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.repository.ListRepository
import javax.inject.Inject

class CreateListUseCase @Inject constructor(
    private val listRepository: ListRepository
) {
    suspend operator fun invoke(title: String, type: ListType, chatId: String? = null, genericStyle: GenericListStyle = GenericListStyle.BULLET): Result<ListData> =
        listRepository.createList(title, type, chatId, genericStyle)
}
