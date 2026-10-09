package com.firestream.chat.ui.chat

import android.net.Uri
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.StickerRepository
import com.firestream.chat.test.MainDispatcherRule
import com.firestream.chat.test.fakes.FakeChatRepository
import com.firestream.chat.test.fakes.FakeMessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * What `ChatMessageSender` does with a picture the keyboard inserted, and with
 * a picked GIF that went through the editor.
 */
class ChatMessageSenderKeyboardContentTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val stickerRepository = mockk<StickerRepository>(relaxed = true)
    private val messageRepository = FakeMessageRepository()
    private val uiState = MutableStateFlow(ChatUiState(session = SessionState(currentUserId = "uid1")))
    private val scope = TestScope(mainDispatcherRule.testDispatcher)
    private var handled = 0

    private fun sender() =
        ChatMessageSender("chat1", FakeChatRepository(), messageRepository, stickerRepository, uiState, scope)

    private fun uri(value: String): Uri = mockk { every { this@mockk.toString() } returns value }

    @Test
    fun `a GIF from the keyboard is sent as a GIF, untouched by the sticker library`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val before = uiState.value.messages.scrollToBottomTrigger

            sender().sendKeyboardContent(uri("content://keyboard/gif"), "image/gif") { handled++ }
            advanceUntilIdle()

            val sent = messageRepository.lastSentMessage
            assertEquals(MessageType.GIF, sent?.type)
            assertEquals("content://keyboard/gif", sent?.localUri)
            assertEquals("image/gif", sent?.mimeType)
            assertEquals(before + 1, uiState.value.messages.scrollToBottomTrigger)
            assertEquals(1, handled)
            coVerify(exactly = 0) { stickerRepository.saveSticker(any()) }
        }

    @Test
    fun `any other picture is saved to the library, sent as a sticker with no pack, and marked used`() =
        runTest(mainDispatcherRule.testDispatcher) {
            coEvery { stickerRepository.saveSticker("content://keyboard/png") } returns Result.success("cat")

            sender().sendKeyboardContent(uri("content://keyboard/png"), "image/png") { handled++ }
            advanceUntilIdle()

            val sent = messageRepository.lastSentMessage
            assertEquals(MessageType.STICKER, sent?.type)
            assertEquals("cat", sent?.stickerId)
            assertNull(sent?.stickerPackId)
            coVerify(exactly = 1) { stickerRepository.markUsed("cat") }
            assertEquals(1, handled)
        }

    @Test
    fun `a picture that cannot become a sticker sends nothing and says why`() =
        runTest(mainDispatcherRule.testDispatcher) {
            coEvery { stickerRepository.saveSticker(any()) } returns
                Result.failure(IllegalStateException("That picture could not be read"))

            sender().sendKeyboardContent(uri("content://keyboard/png"), "image/png") { handled++ }
            advanceUntilIdle()

            assertNull(messageRepository.lastSentMessage)
            assertEquals("That picture could not be read", uiState.value.session.error?.message)
            assertEquals(1, handled)
        }

    @Test
    fun `a GIF the repository refuses surfaces the error and still releases the keyboard's grant`() =
        runTest(mainDispatcherRule.testDispatcher) {
            messageRepository.nextFailure = IllegalStateException("GIFs over 8 MB can't be sent")

            sender().sendKeyboardContent(uri("content://keyboard/gif"), "image/gif") { handled++ }
            advanceUntilIdle()

            assertEquals("GIFs over 8 MB can't be sent", uiState.value.session.error?.message)
            assertEquals(1, handled)
        }

    @Test
    fun `content that is no picture is dropped, and its grant released at once`() =
        runTest(mainDispatcherRule.testDispatcher) {
            sender().sendKeyboardContent(uri("content://keyboard/clip"), "video/mp4") { handled++ }

            assertEquals(1, handled)
            advanceUntilIdle()
            assertNull(messageRepository.lastSentMessage)
        }

    @Test
    fun `leaving the chat in the middle of a save still releases the grant`() =
        runTest(mainDispatcherRule.testDispatcher) {
            coEvery { stickerRepository.saveSticker(any()) } coAnswers { awaitCancellation() }

            sender().sendKeyboardContent(uri("content://keyboard/png"), "image/png") { handled++ }
            scope.testScheduler.runCurrent()
            assertEquals(0, handled)
            scope.cancel()
            scope.testScheduler.runCurrent()

            assertEquals(1, handled)
        }

    @Test
    fun `a picked item goes to the repository under the type it is sent as`() =
        runTest(mainDispatcherRule.testDispatcher) {
            sender().sendMediaMessages(listOf(PendingMedia(uri("content://gallery/funny"), "image/gif")))
            advanceUntilIdle()

            assertEquals("image/gif", messageRepository.sentMedia.single().mimeType)
        }
}
