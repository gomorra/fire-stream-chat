package com.firestream.chat.ui.chat

import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.usecase.chat.CheckGroupPermissionUseCase
import com.firestream.chat.test.MainDispatcherRule
import com.firestream.chat.test.fakes.FakeChatRepository
import com.firestream.chat.test.fakes.FakeConnectivityObserver
import com.firestream.chat.test.fakes.FakeUserRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatInfoManagerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val userRepository = FakeUserRepository()
    private val chatRepository = FakeChatRepository()
    private val listRepository = mockk<ListRepository>(relaxed = true)
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)
    private val checkGroupPermissionUseCase = mockk<CheckGroupPermissionUseCase>(relaxed = true)

    private val uiState = MutableStateFlow(ChatUiState(session = SessionState(currentUserId = "uid1")))

    private val connectivityObserver = FakeConnectivityObserver()

    private fun manager(recipientId: String = "recipient1") = ChatInfoManager(
        chatId = "chat1",
        recipientId = recipientId,
        chatRepository = chatRepository,
        listRepository = listRepository,
        userRepository = userRepository,
        preferencesDataStore = preferencesDataStore,
        checkGroupPermissionUseCase = checkGroupPermissionUseCase,
        connectivityObserver = connectivityObserver,
        _uiState = uiState,
        scope = TestScope(mainDispatcherRule.testDispatcher),
    )

    @Test
    fun `refreshBlockState sets isRecipientBlocked true when user is blocked`() = runTest {
        userRepository.setBlocked("recipient1", true)

        manager().refreshBlockState()

        assertTrue(uiState.value.session.isRecipientBlocked)
    }

    @Test
    fun `refreshBlockState sets isRecipientBlocked false when user is not blocked`() = runTest {
        userRepository.setBlocked("recipient1", false)

        manager().refreshBlockState()

        assertFalse(uiState.value.session.isRecipientBlocked)
    }

    @Test
    fun `refreshBlockState defaults to false when isUserBlocked throws`() = runTest {
        // Degrade to "not blocked" on repository failure so the chat screen still opens.
        userRepository.blockCheckError = IllegalStateException("network down")

        manager().refreshBlockState()

        assertFalse(uiState.value.session.isRecipientBlocked)
    }

    @Test
    fun `refreshBlockState is no-op for blank recipientId (group chats)`() = runTest {
        // Querying an empty userId would target a non-existent Firestore document; skip entirely.
        userRepository.setBlocked("", true) // would be true if we asked

        val initialState = uiState.value
        manager(recipientId = "").refreshBlockState()

        assertEquals(initialState, uiState.value)
    }

    // ── seedRecipientFromCache: instant top-bar paint on chat open ──────────────

    @Test
    fun `start seeds recipient name and avatar from cache before any live emission`() = runTest {
        // A previously-seen recipient is in the local cache. observeUser is never
        // emitted here, so a passing assertion proves the top bar paints from
        // cache and does not depend on the async live stream.
        userRepository.setUser(
            User(uid = "recipient1", displayName = "Alice", avatarUrl = "https://x/a.jpg", localAvatarPath = "/cache/a.jpg")
        )

        manager().start()

        assertEquals("Alice", uiState.value.session.chatName)
        assertEquals("https://x/a.jpg", uiState.value.session.recipientAvatarUrl)
        assertEquals("/cache/a.jpg", uiState.value.session.recipientLocalAvatarPath)
    }

    @Test
    fun `live emission overrides the cache seed`() = runTest {
        userRepository.setUser(User(uid = "recipient1", displayName = "Alice", avatarUrl = "https://x/a.jpg"))

        manager().start()
        assertEquals("Alice", uiState.value.session.chatName)

        userRepository.emitUser(
            User(uid = "recipient1", displayName = "Alice Updated", avatarUrl = "https://x/a2.jpg")
        )

        assertEquals("Alice Updated", uiState.value.session.chatName)
        assertEquals("https://x/a2.jpg", uiState.value.session.recipientAvatarUrl)
    }

    @Test
    fun `start does not seed for blank recipientId (group chats)`() = runTest {
        // A cache entry exists under the empty id, but the group-chat path must
        // not query it — chatName stays null until loadChatInfo fills it.
        userRepository.setUser(User(uid = "", displayName = "ShouldNotAppear"))

        manager(recipientId = "").start()

        assertNull(uiState.value.session.chatName)
    }

    // ── Connectivity: the display-only "Waiting for network…" hint ─────────────

    @Test
    fun `start leaves isOffline false while the device has a validated network`() = runTest {
        manager().start()

        assertFalse(uiState.value.session.isOffline)
    }

    @Test
    fun `losing the network sets isOffline and regaining it clears the hint`() = runTest {
        manager().start()

        connectivityObserver.setOnline(false)
        assertTrue(uiState.value.session.isOffline)

        connectivityObserver.setOnline(true)
        assertFalse(uiState.value.session.isOffline)
    }

    @Test
    fun `a chat opened while offline shows the hint without waiting for a change`() = runTest {
        // The observer is a StateFlow, so the current value arrives on collect —
        // entering a chat in airplane mode must not need a network event first.
        connectivityObserver.setOnline(false)

        manager().start()

        assertTrue(uiState.value.session.isOffline)
    }

    @Test
    fun `connectivity is observed in group chats too`() = runTest {
        // observeConnectivity must sit outside the recipientId guard — a group
        // chat has no 1:1 recipient but queues sends the same way.
        manager(recipientId = "").start()

        connectivityObserver.setOnline(false)

        assertTrue(uiState.value.session.isOffline)
    }

    @Test
    fun `refreshBlockState transitions from blocked to unblocked on second call`() = runTest {
        userRepository.setBlocked("recipient1", true)
        val mgr = manager()
        mgr.refreshBlockState()
        assertTrue(uiState.value.session.isRecipientBlocked)

        userRepository.setBlocked("recipient1", false)
        mgr.refreshBlockState()

        assertFalse(uiState.value.session.isRecipientBlocked)
    }
}
