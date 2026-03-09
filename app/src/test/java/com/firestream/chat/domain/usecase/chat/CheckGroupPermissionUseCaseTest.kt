package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.GroupPermissions
import com.firestream.chat.domain.model.GroupRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CheckGroupPermissionUseCaseTest {

    private lateinit var useCase: CheckGroupPermissionUseCase

    @Before
    fun setUp() {
        useCase = CheckGroupPermissionUseCase()
    }

    private fun groupChat(
        owner: String = "owner1",
        admins: List<String> = listOf("owner1", "admin1"),
        participants: List<String> = listOf("owner1", "admin1", "member1"),
        permissions: GroupPermissions = GroupPermissions()
    ) = Chat(
        id = "chat1",
        type = ChatType.GROUP,
        participants = participants,
        admins = admins,
        owner = owner,
        permissions = permissions
    )

    @Test
    fun `getUserRole returns OWNER for owner`() {
        val chat = groupChat()
        assertEquals(GroupRole.OWNER, useCase.getUserRole(chat, "owner1"))
    }

    @Test
    fun `getUserRole returns ADMIN for admin`() {
        val chat = groupChat()
        assertEquals(GroupRole.ADMIN, useCase.getUserRole(chat, "admin1"))
    }

    @Test
    fun `getUserRole returns MEMBER for regular participant`() {
        val chat = groupChat()
        assertEquals(GroupRole.MEMBER, useCase.getUserRole(chat, "member1"))
    }

    @Test
    fun `canSendMessages returns true for all with default permissions`() {
        val chat = groupChat()
        assertTrue(useCase.canSendMessages(chat, "owner1"))
        assertTrue(useCase.canSendMessages(chat, "admin1"))
        assertTrue(useCase.canSendMessages(chat, "member1"))
    }

    @Test
    fun `canSendMessages in announcement mode blocks members`() {
        val chat = groupChat(permissions = GroupPermissions(isAnnouncementMode = true))
        assertTrue(useCase.canSendMessages(chat, "owner1"))
        assertTrue(useCase.canSendMessages(chat, "admin1"))
        assertFalse(useCase.canSendMessages(chat, "member1"))
    }

    @Test
    fun `canSendMessages with ADMIN restriction blocks members`() {
        val chat = groupChat(permissions = GroupPermissions(sendMessages = GroupRole.ADMIN))
        assertTrue(useCase.canSendMessages(chat, "owner1"))
        assertTrue(useCase.canSendMessages(chat, "admin1"))
        assertFalse(useCase.canSendMessages(chat, "member1"))
    }

    @Test
    fun `canEditGroupInfo with default permissions blocks members`() {
        val chat = groupChat()
        assertTrue(useCase.canEditGroupInfo(chat, "owner1"))
        assertTrue(useCase.canEditGroupInfo(chat, "admin1"))
        assertFalse(useCase.canEditGroupInfo(chat, "member1"))
    }

    @Test
    fun `canEditGroupInfo with MEMBER permission allows all`() {
        val chat = groupChat(permissions = GroupPermissions(editGroupInfo = GroupRole.MEMBER))
        assertTrue(useCase.canEditGroupInfo(chat, "member1"))
    }

    @Test
    fun `canAddMembers with default permissions blocks members`() {
        val chat = groupChat()
        assertTrue(useCase.canAddMembers(chat, "owner1"))
        assertTrue(useCase.canAddMembers(chat, "admin1"))
        assertFalse(useCase.canAddMembers(chat, "member1"))
    }

    @Test
    fun `hasPermission compares role ordinals correctly`() {
        assertTrue(useCase.hasPermission(GroupRole.OWNER, GroupRole.ADMIN))
        assertTrue(useCase.hasPermission(GroupRole.ADMIN, GroupRole.ADMIN))
        assertFalse(useCase.hasPermission(GroupRole.MEMBER, GroupRole.ADMIN))
        assertTrue(useCase.hasPermission(GroupRole.OWNER, GroupRole.MEMBER))
    }
}
