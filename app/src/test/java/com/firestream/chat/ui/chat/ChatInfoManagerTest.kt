package com.firestream.chat.ui.chat

import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.usecase.chat.CheckGroupPermissionUseCase
import com.firestream.chat.test.MainDispatcherRule
import com.firestream.chat.test.fakes.FakeChatRepository
import com.firestream.chat.test.fakes.FakeUserRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun manager(recipientId: String = "recipient1") = ChatInfoManager(
        chatId = "chat1",
        recipientId = recipientId,
        chatRepository = chatRepository,
        listRepository = listRepository,
        userRepository = userRepository,
        preferencesDataStore = preferencesDataStore,
        checkGroupPermissionUseCase = checkGroupPermissionUseCase,
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
