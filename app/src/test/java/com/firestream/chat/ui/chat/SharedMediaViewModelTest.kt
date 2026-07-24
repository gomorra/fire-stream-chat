package com.firestream.chat.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.test.fakes.FakeMessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedMediaViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val messageRepository = FakeMessageRepository()

    private val chatId = "chat-1"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        messageRepository.reset()
    }

    private fun viewModel() = SharedMediaViewModel(
        savedStateHandle = SavedStateHandle(mapOf("chatId" to chatId)),
        messageRepository = messageRepository,
        appScope = CoroutineScope(testDispatcher),
    )

    private fun imageMessage(
        id: String,
        timestamp: Long,
        mediaUrl: String? = "https://cdn/$id.jpg",
        localUri: String? = null,
        type: MessageType = MessageType.IMAGE,
        deletedAt: Long? = null,
    ) = Message(
        id = id,
        chatId = chatId,
        type = type,
        mediaUrl = mediaUrl,
        localUri = localUri,
        timestamp = timestamp,
        deletedAt = deletedAt,
    )

    @Test
    fun `includes only non-deleted image messages with a media url`() = runTest(testDispatcher) {
        messageRepository.emit(
            chatId,
            listOf(
                imageMessage("img", timestamp = 1),
                imageMessage("video", timestamp = 2, type = MessageType.VIDEO),
                imageMessage("no-url", timestamp = 3, mediaUrl = null),
                imageMessage("deleted", timestamp = 4, deletedAt = 100L),
                Message(id = "text", chatId = chatId, type = MessageType.TEXT, timestamp = 5),
            ),
        )

        val vm = viewModel()
        val job = launch { vm.media.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("https://cdn/img.jpg"), vm.media.value.map { it.mediaUrl })
        job.cancel()
    }

    @Test
    fun `orders newest first and carries localUri`() = runTest(testDispatcher) {
        messageRepository.emit(
            chatId,
            listOf(
                imageMessage("old", timestamp = 10, localUri = "/data/old.jpg"),
                imageMessage("new", timestamp = 30, localUri = null),
                imageMessage("mid", timestamp = 20, localUri = "/data/mid.jpg"),
            ),
        )

        val vm = viewModel()
        val job = launch { vm.media.collect {} }
        advanceUntilIdle()

        val result = vm.media.value
        assertEquals(listOf("new", "mid", "old").map { "https://cdn/$it.jpg" }, result.map { it.mediaUrl })
        assertEquals(listOf(null, "/data/mid.jpg", "/data/old.jpg"), result.map { it.localUri })
        job.cancel()
    }

    @Test
    fun `opening the gallery triggers a local-copy backfill for the chat`() = runTest(testDispatcher) {
        viewModel()
        advanceUntilIdle()

        assertEquals(listOf(chatId), messageRepository.ensureLocalCopiesCalls)
    }
}
