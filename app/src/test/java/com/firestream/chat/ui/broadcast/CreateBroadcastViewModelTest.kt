package com.firestream.chat.ui.broadcast

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ContactRepository
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
class CreateBroadcastViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var authRepository: AuthRepository
    private lateinit var contactRepository: ContactRepository
    private lateinit var chatRepository: ChatRepository

    private val contacts = listOf(
        Contact(uid = "user1", displayName = "Alice", phoneNumber = "+1111"),
        Contact(uid = "user2", displayName = "Bob", phoneNumber = "+2222"),
        Contact(uid = "user3", displayName = "Charlie", phoneNumber = "+3333")
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        authRepository = mockk()
        contactRepository = mockk()
        chatRepository = mockk()
        every { authRepository.currentUserId } returns "creator"
        coEvery { contactRepository.syncContacts() } returns Result.success(contacts)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = CreateBroadcastViewModel(
        authRepository = authRepository,
        contactRepository = contactRepository,
        chatRepository = chatRepository
    )

    @Test
    fun `loads contacts on init`() = runTest {
        val viewModel = createViewModel()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(3, viewModel.uiState.value.contacts.size)
        assertEquals(3, viewModel.uiState.value.filteredContacts.size)
    }

    @Test
    fun `toggleSelection selects and deselects contacts`() = runTest {
        val viewModel = createViewModel()

        viewModel.toggleSelection("user1")
        assertTrue("user1" in viewModel.uiState.value.selectedIds)

        viewModel.toggleSelection("user1")
        assertFalse("user1" in viewModel.uiState.value.selectedIds)
    }

    @Test
    fun `onSearchQueryChange filters contacts`() = runTest {
        val viewModel = createViewModel()

        viewModel.onSearchQueryChange("ali")

        assertEquals(1, viewModel.uiState.value.filteredContacts.size)
        assertEquals("Alice", viewModel.uiState.value.filteredContacts.first().displayName)
    }

    @Test
    fun `onSearchQueryChange with blank restores all contacts`() = runTest {
        val viewModel = createViewModel()
        viewModel.onSearchQueryChange("ali")
        viewModel.onSearchQueryChange("")

        assertEquals(3, viewModel.uiState.value.filteredContacts.size)
    }

    @Test
    fun `createBroadcast sets error when no recipients selected`() = runTest {
        val viewModel = createViewModel()

        viewModel.createBroadcast {}

        assertEquals("Select at least one recipient", viewModel.uiState.value.error?.message)
    }

    @Test
    fun `createBroadcast calls use case with selected recipients`() = runTest {
        val chat = Chat(id = "b1", type = ChatType.BROADCAST, name = "Test Broadcast")
        coEvery { chatRepository.createBroadcastList(any(), any()) } returns Result.success(chat)
        val viewModel = createViewModel()
        viewModel.toggleSelection("user1")
        viewModel.toggleSelection("user2")
        viewModel.onNameChange("My List")

        var createdId: String? = null
        viewModel.createBroadcast { createdId = it }

        assertEquals("b1", createdId)
        assertFalse(viewModel.uiState.value.isCreating)
    }

    @Test
    fun `createBroadcast uses default name when blank`() = runTest {
        val chat = Chat(id = "b1", type = ChatType.BROADCAST, name = "Broadcast list")
        coEvery { chatRepository.createBroadcastList("Broadcast list", listOf("user1")) } returns Result.success(chat)
        val viewModel = createViewModel()
        viewModel.toggleSelection("user1")
        // name is blank by default

        var createdId: String? = null
        viewModel.createBroadcast { createdId = it }

        assertEquals("b1", createdId)
    }

    @Test
    fun `createBroadcast sets error on failure`() = runTest {
        coEvery { chatRepository.createBroadcastList(any(), any()) } returns Result.failure(Exception("Network error"))
        val viewModel = createViewModel()
        viewModel.toggleSelection("user1")

        viewModel.createBroadcast {}

        assertEquals("Network error", viewModel.uiState.value.error?.message)
    }

    @Test
    fun `clearError clears the error state`() = runTest {
        val viewModel = createViewModel()
        viewModel.createBroadcast {} // triggers "Select at least one recipient" error

        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `contacts load error is reflected in state`() = runTest {
        coEvery { contactRepository.syncContacts() } returns Result.failure(Exception("Load failed"))

        val viewModel = createViewModel()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("Load failed", viewModel.uiState.value.error?.message)
    }
}
