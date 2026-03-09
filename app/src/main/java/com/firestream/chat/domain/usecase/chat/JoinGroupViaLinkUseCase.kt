package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.repository.ChatRepository
import javax.inject.Inject

class JoinGroupViaLinkUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(inviteToken: String): Result<Chat> =
        chatRepository.joinGroupViaLink(inviteToken)
}
