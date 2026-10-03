package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.entity.ChatEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.ChatSource
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * `getOrCreateChat` leaves a local row behind on both of its branches. A send resolves its
 * target from that row, so a chat handed back without one could not be sent into.
 */
class ChatRepositoryImplGetOrCreateTest {

    private val chatDao = mockk<ChatDao>(relaxed = true)
    private val chatSource = mockk<ChatSource>(relaxed = true)
    private val authSource = mockk<AuthSource>()

    private lateinit var repository: ChatRepositoryImpl

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "uid1"
        coEvery { chatDao.upsertRemote(any()) } returns emptyMap()
        repository = ChatRepositoryImpl(
            chatDao = chatDao,
            messageDao = mockk(relaxed = true),
            chatSource = chatSource,
            authSource = authSource,
            storageSource = mockk(relaxed = true),
            profileImageManager = mockk(relaxed = true)
        )
    }

    @Test
    fun `a chat found on the backend is merged into the local store`() = runTest {
        val existing = Chat(
            id = "chat1",
            type = ChatType.INDIVIDUAL,
            participants = listOf("recipient1", "uid1"),
            createdAt = 1_000L,
            createdBy = "recipient1"
        )
        coEvery { chatSource.findIndividualChat(listOf("recipient1", "uid1"), "uid1") } returns existing

        val result = repository.getOrCreateChat("recipient1")

        assertSame(existing, result.getOrThrow())
        coVerify(exactly = 1) { chatDao.upsertRemote(listOf(ChatEntity.fromDomain(existing))) }
        // A REPLACE insert would wipe isPinned, isArchived, muteUntil and the avatar cache.
        coVerify(exactly = 0) { chatDao.insertChat(any()) }
        coVerify(exactly = 0) { chatSource.createChat(any()) }
    }

    @Test
    fun `a chat that does not exist yet is created and inserted`() = runTest {
        coEvery { chatSource.findIndividualChat(any(), any()) } returns null
        coEvery { chatSource.createChat(any()) } returns "newChat"

        val chat = repository.getOrCreateChat("recipient1").getOrThrow()

        assertEquals("newChat", chat.id)
        assertEquals(ChatType.INDIVIDUAL, chat.type)
        assertEquals(listOf("recipient1", "uid1"), chat.participants)
        assertEquals("uid1", chat.createdBy)
        coVerify(exactly = 1) { chatDao.insertChat(ChatEntity.fromDomain(chat)) }
        coVerify(exactly = 0) { chatDao.upsertRemote(any()) }
    }
}
