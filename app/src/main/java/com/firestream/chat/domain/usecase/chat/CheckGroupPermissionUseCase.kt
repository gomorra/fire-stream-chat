package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.GroupRole
import javax.inject.Inject

class CheckGroupPermissionUseCase @Inject constructor() {

    fun getUserRole(chat: Chat, userId: String): GroupRole {
        return when {
            chat.owner == userId -> GroupRole.OWNER
            userId in chat.admins -> GroupRole.ADMIN
            else -> GroupRole.MEMBER
        }
    }

    fun hasPermission(userRole: GroupRole, requiredRole: GroupRole): Boolean {
        return userRole.ordinal <= requiredRole.ordinal
    }

    fun canSendMessages(chat: Chat, userId: String): Boolean {
        if (chat.permissions.isAnnouncementMode) {
            val role = getUserRole(chat, userId)
            return role == GroupRole.OWNER || role == GroupRole.ADMIN
        }
        return hasPermission(getUserRole(chat, userId), chat.permissions.sendMessages)
    }

    fun canEditGroupInfo(chat: Chat, userId: String): Boolean {
        return hasPermission(getUserRole(chat, userId), chat.permissions.editGroupInfo)
    }

    fun canAddMembers(chat: Chat, userId: String): Boolean {
        return hasPermission(getUserRole(chat, userId), chat.permissions.addMembers)
    }
}
