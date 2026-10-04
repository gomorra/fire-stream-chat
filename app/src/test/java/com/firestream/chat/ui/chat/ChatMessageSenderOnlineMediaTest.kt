package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.OnlineMedia
import com.firestream.chat.domain.model.OnlineMediaKind
import com.firestream.chat.domain.model.OnlineMediaRendition
import com.firestream.chat.domain.repository.StickerRepository
import com.firestream.chat.domain.model.MediaLimitException
import com.firestream.chat.test.MainDispatcherRule
import com.firestream.chat.test.fakes.FakeChatRepository
import com.firestream.chat.test.fakes.FakeMessageRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** What `ChatMessageSender` does with a pick from the GIFs tab or the online stickers. */
class ChatMessageSenderOnlineMediaTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val stickerRepository = mockk<StickerRepository>(relaxed = true)
    private val messageRepository = FakeMessageRepository()
    private val uiState = MutableStateFlow(ChatUiState(session = SessionState(currentUserId = "uid1")))
    private val scope = TestScope(mainDispatcherRule.testDispatcher)
    private var sent = 0

    private fun sender() =
        ChatMessageSender("chat1", FakeChatRepository(), messageRepository, stickerRepository, uiState, scope)

    private fun media(kind: OnlineMediaKind): OnlineMedia {
        val file = OnlineMediaRendition("https://static.klipy.com/wave.webp", 100, 80, "image/webp")
        return OnlineMedia(kind, "wave", "Wave", file, file)
    }

    @Test
    fun `an online sticker is sent pointing at the provider, and the library never hears of it`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val before = uiState.value.messages.scrollToBottomTrigger

            sender().sendOnlineMedia(media(OnlineMediaKind.STICKER)) { sent++ }
            advanceUntilIdle()

            val message = messageRepository.lastSentMessage
            assertEquals(MessageType.STICKER, message?.type)
            assertEquals("https://static.klipy.com/wave.webp", message?.mediaUrl)
            assertNull(message?.stickerId)
            assertEquals(before + 1, uiState.value.messages.scrollToBottomTrigger)
            assertEquals(1, sent)
            coVerify(exactly = 0) { stickerRepository.markUsed(any()) }
        }

    @Test
    fun `a pick the repository refuses says why and is not reported as sent`() =
        runTest(mainDispatcherRule.testDispatcher) {
            messageRepository.nextFailure = MediaLimitException("That is not a KLIPY file")

            sender().sendOnlineMedia(media(OnlineMediaKind.GIF)) { sent++ }
            advanceUntilIdle()

            assertNull(messageRepository.lastSentMessage)
            assertTrue(uiState.value.session.error is AppError.Validation)
            assertEquals(0, sent)
        }
}
