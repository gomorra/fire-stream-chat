package com.firestream.chat.domain.usecase.message

import android.net.Uri
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.MessageRepository
import javax.inject.Inject

class SendMediaMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(chatId: String, uri: Uri, mimeType: String, recipientId: String): Result<Message> {
        return messageRepository.sendMediaMessage(chatId, uri, mimeType, recipientId)
    }
}
