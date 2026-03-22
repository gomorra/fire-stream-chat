package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.repository.ListRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetSharedListsForChatUseCase @Inject constructor(
    private val listRepository: ListRepository
) {
    operator fun invoke(chatId: String): Flow<List<ListData>> =
        listRepository.getSharedListsForChat(chatId)
}
