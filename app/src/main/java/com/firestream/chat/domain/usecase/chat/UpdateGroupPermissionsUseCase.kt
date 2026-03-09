package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.model.GroupPermissions
import com.firestream.chat.domain.repository.ChatRepository
import javax.inject.Inject

class UpdateGroupPermissionsUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(chatId: String, permissions: GroupPermissions): Result<Unit> {
        return chatRepository.updateGroupPermissions(chatId, permissions)
    }
}
