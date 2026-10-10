package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.ChatSource
import com.firestream.chat.test.TestData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * A chat that leaves this device takes its sync state with its messages. A state
 * row left behind would tell the next sync the chat is restored, and a chat that
 * comes back would get only its tail.
 */
class ChatRepositoryImplDeleteTest {

    private val chatDao = mockk<ChatDao>(relaxed = true)
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val chatSource = mockk<ChatSource>(relaxed = true)
    private val authSource = mockk<AuthSource>()

    private lateinit var repository: ChatRepositoryImpl

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "uid1"
        repository = ChatRepositoryImpl(
            chatDao = chatDao,
            messageDao = messageDao,
            chatSource = chatSource,
            authSource = authSource,
            storageSource = mockk(relaxed = true),
            profileImageManager = mockk(relaxed = true)
        )
    }

    // A restore still running for the chat writes its state only while the chat
    // row exists. With the chat row gone first, the delete below it is the last word.
    @Test
    fun `deleting a chat removes the chat row, then its messages and sync state together`() = runTest {
        repository.deleteChat("chat1").getOrThrow()

        coVerifyOrder {
            chatDao.deleteChat("chat1")
            messageDao.deleteChatMessages("chat1")
        }
    }

    @Test
    fun `leaving a group removes its messages and its sync state together`() = runTest {
        coEvery { chatSource.getChat("group1", "uid1") } returns
            TestData.chat(id = "group1", participants = listOf("uid1", "uid2"))

        repository.leaveGroup("group1").getOrThrow()

        coVerify(exactly = 1) { messageDao.deleteChatMessages("group1") }
    }
}
