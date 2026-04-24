package com.firestream.chat.ui.group

import androidx.lifecycle.SavedStateHandle
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.GroupRole
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.test.fakes.FakeChatRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupSettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val chatRepository = FakeChatRepository()
    private lateinit var userRepository: UserRepository
    private lateinit var authRepository: AuthRepository

    private val testChat = Chat(
        id = "chat1",
        type = ChatType.GROUP,
        name = "Test Group",
        participants = listOf("user1", "user2", "user3"),
        admins = listOf("user1"),
        createdBy = "user1"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        userRepository = mockk()
        authRepository = mockk()

        every { authRepository.currentUserId } returns "user1"
        coEvery { userRepository.getUserById("user1") } returns Result.success(User(uid = "user1", displayName = "Alice"))
        coEvery { userRepository.getUserById("user2") } returns Result.success(User(uid = "user2", displayName = "Bob"))
        coEvery { userRepository.getUserById("user3") } returns Result.success(User(uid = "user3", displayName = "Charlie"))

        chatRepository.emit(listOf(testChat))
        chatRepository.chatByIdResult = Result.success(testChat)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        chatRepository.reset()
    }

    private fun createViewModel(): GroupSettingsViewModel {
        val savedStateHandle = SavedStateHandle(mapOf("chatId" to "chat1"))
        return GroupSettingsViewModel(
            savedStateHandle = savedStateHandle,
            chatRepository = chatRepository,
            userRepository = userRepository,
            authRepository = authRepository
        )
    }

    @Test
    fun `loads group details on init`() = runTest {
        val viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Test Group", state.chat?.name)
        assertEquals(GroupRole.OWNER, state.currentUserRole)
        assertEquals(3, state.members.size)
    }

    @Test
    fun `owner role is detected correctly`() = runTest {
        val viewModel = createViewModel()

        assertEquals(GroupRole.OWNER, viewModel.uiState.value.currentUserRole)
    }

    @Test
    fun `admin role is detected correctly`() = runTest {
        every { authRepository.currentUserId } returns "user2"
        val chatWithUser2Admin = testChat.copy(admins = listOf("user1", "user2"))
        chatRepository.chatByIdResult = Result.success(chatWithUser2Admin)

        val viewModel = createViewModel()

        assertEquals(GroupRole.ADMIN, viewModel.uiState.value.currentUserRole)
    }

    @Test
    fun `member role is detected correctly`() = runTest {
        every { authRepository.currentUserId } returns "user3"

        val viewModel = createViewModel()

        assertEquals(GroupRole.MEMBER, viewModel.uiState.value.currentUserRole)
    }

    @Test
    fun `updateDescription updates chat state`() = runTest {
        val viewModel = createViewModel()

        viewModel.updateDescription("Updated desc")

        assertEquals("Updated desc", viewModel.uiState.value.chat?.description)
    }

    @Test
    fun `generateInviteLink updates chat state`() = runTest {
        val viewModel = createViewModel()

        viewModel.generateInviteLink()

        assertEquals("invite-chat1", viewModel.uiState.value.chat?.inviteLink)
        assertTrue(viewModel.uiState.value.inviteLinkGenerated)
    }

    @Test
    fun `revokeInviteLink clears invite link`() = runTest {
        val chatWithLink = testChat.copy(inviteLink = "old-token")
        chatRepository.chatByIdResult = Result.success(chatWithLink)
        val viewModel = createViewModel()

        viewModel.revokeInviteLink()

        assertNull(viewModel.uiState.value.chat?.inviteLink)
        assertFalse(viewModel.uiState.value.inviteLinkGenerated)
    }

    @Test
    fun `leaveGroup sets leftGroup flag`() = runTest {
        val viewModel = createViewModel()

        viewModel.leaveGroup()

        assertTrue(viewModel.uiState.value.leftGroup)
    }

    @Test
    fun `setRequireApproval updates chat state`() = runTest {
        val viewModel = createViewModel()

        viewModel.setRequireApproval(true)

        assertTrue(viewModel.uiState.value.chat?.requireApproval == true)
    }

    @Test
    fun `error is set on failure`() = runTest {
        val viewModel = createViewModel()
        chatRepository.nextFailure = Exception("Network error")

        viewModel.updateDescription("desc")

        assertEquals("Network error", viewModel.uiState.value.error?.message)
    }
}
