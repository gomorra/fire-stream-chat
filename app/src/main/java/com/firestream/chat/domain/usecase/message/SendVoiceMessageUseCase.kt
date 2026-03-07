package com.firestream.chat.domain.usecase.message

import android.net.Uri
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.MessageRepository
import javax.inject.Inject

class SendVoiceMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(
        chatId: String,
        uri: Uri,
        recipientId: String,
        durationSeconds: Int
    ): Result<Message> = messageRepository.sendVoiceMessage(chatId, uri, recipientId, durationSeconds)
}
