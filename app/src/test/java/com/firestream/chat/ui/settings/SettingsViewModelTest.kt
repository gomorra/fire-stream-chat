package com.firestream.chat.ui.settings

import com.firestream.chat.data.local.AppTheme
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.UserRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var authRepository: AuthRepository
    private lateinit var userRepository: UserRepository
    private lateinit var preferencesDataStore: PreferencesDataStore
    private lateinit var viewModel: SettingsViewModel

    private val testUser = User(
        uid = "user1",
        displayName = "Alice",
        phoneNumber = "+1234567890",
        statusText = "Hey there!"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        authRepository = mockk()
        userRepository = mockk()
        preferencesDataStore = mockk()

        every { authRepository.currentUserId } returns "user1"
        every { userRepository.observeUser("user1") } returns flowOf(testUser)
        every { preferencesDataStore.appThemeFlow } returns flowOf(AppTheme.SYSTEM)

        viewModel = SettingsViewModel(
            authRepository = authRepository,
            userRepository = userRepository,
            preferencesDataStore = preferencesDataStore
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads current user and theme`() = runTest {
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.currentUser)
        assertEquals("Alice", state.currentUser?.displayName)
        assertEquals(AppTheme.SYSTEM, state.appTheme)
    }

    @Test
    fun `setTheme calls datastore with correct theme`() = runTest {
        io.mockk.coEvery { preferencesDataStore.setAppTheme(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setTheme(AppTheme.DARK)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setAppTheme(AppTheme.DARK) }
    }

    @Test
    fun `setTheme to LIGHT calls datastore correctly`() = runTest {
        io.mockk.coEvery { preferencesDataStore.setAppTheme(AppTheme.LIGHT) } returns Unit
        advanceUntilIdle()

        viewModel.setTheme(AppTheme.LIGHT)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setAppTheme(AppTheme.LIGHT) }
    }
}
