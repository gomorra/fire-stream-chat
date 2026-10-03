package com.firestream.chat.ui.chat

import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.test.fakes.testSticker
import com.firestream.chat.test.fakes.testStickerPack
import com.firestream.chat.domain.reminder.DateTimeDetector
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.ReminderRepository
import com.firestream.chat.domain.repository.StickerRepository
import com.firestream.chat.domain.usecase.chat.CheckGroupPermissionUseCase
import com.firestream.chat.test.MainDispatcherRule
import com.firestream.chat.test.fakes.FakeChatRepository
import com.firestream.chat.test.fakes.FakeConnectivityObserver
import com.firestream.chat.test.fakes.FakeMessageRepository
import com.firestream.chat.test.fakes.FakeUserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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

/**
 * What the chat screen's managers do for stickers: `ChatInfoManager` mirrors
 * the library, `ChatMessageSender` sends a pick, and `ChatMessageActions` flips
 * a favourite.
 */
class ChatStickerManagersTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private fun sticker(id: String) = testSticker(id, "😺")

    private fun pack(id: String, kind: StickerPackKind, vararg stickers: Sticker) = testStickerPack(id, kind, *stickers)

    private val packsFlow = MutableStateFlow<List<StickerPack>>(emptyList())
    private val recentsFlow = MutableStateFlow<List<Sticker>>(emptyList())
    private val stickerRepository = mockk<StickerRepository>(relaxed = true) {
        every { observePacks() } returns packsFlow
        every { observeRecents() } returns recentsFlow
        coEvery { setFavourite(any(), any()) } returns Result.success(Unit)
    }
    private val messageRepository = FakeMessageRepository()
    private val uiState = MutableStateFlow(ChatUiState(session = SessionState(currentUserId = "uid1")))
    private val scope = TestScope(mainDispatcherRule.testDispatcher)

    private fun infoManager() = ChatInfoManager(
        chatId = "chat1",
        partnerIdHint = "",
        chatRepository = FakeChatRepository(),
        listRepository = mockk<ListRepository>(relaxed = true),
        userRepository = FakeUserRepository(),
        stickerRepository = stickerRepository,
        preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true),
        checkGroupPermissionUseCase = mockk<CheckGroupPermissionUseCase>(relaxed = true),
        connectivityObserver = FakeConnectivityObserver(),
        _uiState = uiState,
        scope = scope,
    )

    private fun sender() =
        ChatMessageSender("chat1", FakeChatRepository(), messageRepository, stickerRepository, uiState, scope)

    private fun actions() = ChatMessageActions(
        chatId = "chat1",
        partnerIdHint = "",
        messageRepository = messageRepository,
        reminderRepository = mockk<ReminderRepository>(relaxed = true),
        stickerRepository = stickerRepository,
        dateTimeDetector = mockk<DateTimeDetector>(relaxed = true),
        _uiState = uiState,
        scope = scope,
    )

    // ── ChatInfoManager ──────────────────────────────────────────────────────

    @Test
    fun `the library is mirrored into the overlays slice, with the favourite ids`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val cat = sticker("cat")
            val dog = sticker("dog")
            infoManager().start()

            packsFlow.value = listOf(
                pack("animals", StickerPackKind.USER, cat, dog),
                pack("favs", StickerPackKind.FAVOURITES, dog),
            )
            recentsFlow.value = listOf(dog)
            advanceUntilIdle()

            val overlays = uiState.value.overlays
            assertEquals(listOf("animals", "favs"), overlays.stickerPacks.map { it.id })
            assertEquals(listOf(dog), overlays.recentStickers)
            assertEquals(setOf("dog"), overlays.favouriteStickerIds)
        }

    @Test
    fun `a sticker taken out of the favourites leaves the favourite ids`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val cat = sticker("cat")
            infoManager().start()
            packsFlow.value = listOf(pack("favs", StickerPackKind.FAVOURITES, cat))
            advanceUntilIdle()

            packsFlow.value = listOf(pack("favs", StickerPackKind.FAVOURITES))
            advanceUntilIdle()

            assertTrue(uiState.value.overlays.favouriteStickerIds.isEmpty())
        }

    // ── ChatMessageSender ────────────────────────────────────────────────────

    @Test
    fun `sendSticker sends by id and pack, and marks the sticker used`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val before = uiState.value.messages.scrollToBottomTrigger

            sender().sendSticker("cat", "animals")
            advanceUntilIdle()

            val sent = messageRepository.lastSentMessage
            assertEquals(MessageType.STICKER, sent?.type)
            assertEquals("cat", sent?.stickerId)
            assertEquals("animals", sent?.stickerPackId)
            assertEquals(before + 1, uiState.value.messages.scrollToBottomTrigger)
            coVerify(exactly = 1) { stickerRepository.markUsed("cat") }
        }

    @Test
    fun `a sticker the repository refuses is not marked used and surfaces the error`() =
        runTest(mainDispatcherRule.testDispatcher) {
            messageRepository.nextFailure = IllegalStateException("That sticker is no longer in your library")

            sender().sendSticker("gone", null)
            advanceUntilIdle()

            coVerify(exactly = 0) { stickerRepository.markUsed(any()) }
            val error = uiState.value.session.error
            assertTrue(error is AppError)
            assertEquals("That sticker is no longer in your library", error?.message)
        }

    // ── ChatMessageActions ───────────────────────────────────────────────────

    @Test
    fun `a library sticker is favourited without a download`() = runTest(mainDispatcherRule.testDispatcher) {
        var line: String? = null

        actions().setStickerFavourite("cat", favourite = true) { line = it }
        advanceUntilIdle()

        coVerify { stickerRepository.setFavourite("cat", true) }
        assertEquals("Added to favourites", line)
    }

    @Test
    fun `a received sticker without a local file is fetched before it is favourited`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val message = Message(id = "m1", chatId = "chat1", type = MessageType.STICKER, stickerId = "cat")
            var fetched: Message? = null
            messageRepository.ensureLocalFileResult = { fetched = it; Result.success("/stickers/cat.webp") }

            actions().setStickerFavourite("cat", favourite = true, message = message) {}
            advanceUntilIdle()

            assertEquals(message, fetched)
            coVerify { stickerRepository.setFavourite("cat", true) }
        }

    @Test
    fun `a refused sticker is not favourited`() = runTest(mainDispatcherRule.testDispatcher) {
        val message = Message(id = "m1", chatId = "chat1", type = MessageType.STICKER, stickerId = "cat")
        var line: String? = null
        // The fake's default: ensureLocalFile fails.

        actions().setStickerFavourite("cat", favourite = true, message = message) { line = it }
        advanceUntilIdle()

        coVerify(exactly = 0) { stickerRepository.setFavourite(any(), any()) }
        assertEquals("Couldn't save this sticker", line)
    }

    @Test
    fun `taking a sticker out of the favourites needs no local file`() = runTest(mainDispatcherRule.testDispatcher) {
        val message = Message(id = "m1", chatId = "chat1", type = MessageType.STICKER, stickerId = "cat")
        var line: String? = null

        actions().setStickerFavourite("cat", favourite = false, message = message) { line = it }
        advanceUntilIdle()

        coVerify { stickerRepository.setFavourite("cat", false) }
        assertEquals("Removed from favourites", line)
        assertNull(uiState.value.session.error)
    }
}
