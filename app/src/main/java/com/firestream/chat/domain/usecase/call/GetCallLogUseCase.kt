package com.firestream.chat.domain.usecase.call

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetCallLogUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    operator fun invoke(): Flow<List<Message>> = messageRepository.getCallLog()
}
