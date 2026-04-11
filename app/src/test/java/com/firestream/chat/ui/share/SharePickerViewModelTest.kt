package com.firestream.chat.ui.share

import android.content.Intent
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.data.share.ShareContentResolver
import com.firestream.chat.data.share.SharedContentHolder
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.SharedContent
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SharePickerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var chatRepository: ChatRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var linkPreviewSource: LinkPreviewSource
    private lateinit var authRepository: AuthRepository
    private lateinit var userRepository: UserRepository
    private lateinit var sharedContentHolder: SharedContentHolder
    private lateinit var shareContentResolver: ShareContentResolver

    private val me = "user1"
    private val peer = "user2"
    private val otherGroupMember = "user3"

    private val individualChat = Chat(
        id = "chat-individual",
        type = ChatType.INDIVIDUAL,
        participants = listOf(me, peer)
    )
    private val groupChat = Chat(
        id = "chat-group",
        type = ChatType.GROUP,
        participants = listOf(me, peer, otherGroupMember),
        name = "Group"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        chatRepository = mockk()
        messageRepository = mockk()
        linkPreviewSource = mockk(relaxed = true)
        authRepository = mockk()
        userRepository = mockk()
        sharedContentHolder = mockk()
        shareContentResolver = mockk()

        every { authRepository.currentUserId } returns me
        every { chatRepository.getChats() } returns flowOf(listOf(individualChat, groupChat))
        coEvery { userRepository.getUserById(any()) } returns Result.success(User(uid = peer))
        every { linkPreviewSource.extractUrl(any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): SharePickerViewModel = SharePickerViewModel(
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        linkPreviewSource = linkPreviewSource,
        authRepository = authRepository,
        userRepository = userRepository,
        sharedContentHolder = sharedContentHolder,
        shareContentResolver = shareContentResolver
    )

    @Test
    fun `send failure surfaces error and does not navigate`() = runTest {
        every { sharedContentHolder.consumeIntent() } returns mockk<Intent>()
        coEvery { shareContentResolver.resolve(any()) } returns SharedContent.Text("hi")
        coEvery {
            messageRepository.sendMessage(any(), any(), any(), any(), any(), any())
        } returns Result.failure(IOException("boom"))

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.toggleChatSelection(individualChat.id)

        var onDoneCalled = false
        viewModel.send { _, _ -> onDoneCalled = true }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("isSending should reset on failure", state.isSending)
        assertNotNull("error should be set on failure", state.error)
        assertFalse("onDone must not fire when send fails", onDoneCalled)
    }

    @Test
    fun `send to group uses empty recipientId`() = runTest {
        every { sharedContentHolder.consumeIntent() } returns mockk<Intent>()
        coEvery { shareContentResolver.resolve(any()) } returns SharedContent.Text("hi")
        coEvery {
            messageRepository.sendMessage(
                chatId = groupChat.id,
                content = "hi",
                recipientId = "",
                replyToId = any(),
                mentions = any(),
                emojiSizes = any()
            )
        } returns Result.success(Message(id = "m1", chatId = groupChat.id, content = "hi"))

        val viewModel = buildViewModel()
        advanceUntilIdle()
        viewModel.toggleChatSelection(groupChat.id)

        var doneChatId: String? = null
        viewModel.send { chatId, _ -> doneChatId = chatId }
        advanceUntilIdle()

        assertEquals(groupChat.id, doneChatId)
        // Confirms the group branch passed "" and NOT otherGroupMember.
        coVerify(exactly = 1) {
            messageRepository.sendMessage(
                chatId = groupChat.id,
                content = "hi",
                recipientId = "",
                replyToId = any(),
                mentions = any(),
                emojiSizes = any()
            )
        }
    }

    @Test
    fun `resolve failure sets Error preview state`() = runTest {
        every { sharedContentHolder.consumeIntent() } returns mockk<Intent>()
        coEvery { shareContentResolver.resolve(any()) } throws SecurityException("no perm")

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(PreviewState.Error, state.previewState)
        assertNotNull(state.error)
        assertNull("no content should be set when resolve fails", state.sharedContent)
    }

    @Test
    fun `missing intent marks preview Empty`() = runTest {
        every { sharedContentHolder.consumeIntent() } returns null

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals(PreviewState.Empty, viewModel.uiState.value.previewState)
    }
}
