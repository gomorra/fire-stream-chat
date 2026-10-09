package com.firestream.chat.ui.main

import com.firestream.chat.data.call.FullScreenIntentAccess
import com.firestream.chat.data.local.PreferencesDataStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FullScreenAccessViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher)

    private val access = mockk<FullScreenIntentAccess>(relaxed = true)
    private val preferences = mockk<PreferencesDataStore>()
    private val dismissed = MutableStateFlow(false)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { access.apiLevel } returns 34
        every { access.isGranted() } returns false
        every { preferences.fullScreenAccessPromptDismissedFlow } returns dismissed
        coEvery { preferences.setFullScreenAccessPromptDismissed() } answers { dismissed.value = true }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Nothing collects the prompt state here. The screen is out of the composition while the
     * app's own Settings screen is open, and the state must still be right the moment it is back.
     */
    private fun TestScope.viewModel(): FullScreenAccessViewModel =
        FullScreenAccessViewModel(access, preferences, appScope = backgroundScope)

    @Test
    fun `the prompt waits until the remembered Not now has been read`() = scope.runTest {
        every { preferences.fullScreenAccessPromptDismissedFlow } returns emptyFlow()
        val viewModel = viewModel()

        viewModel.refresh()

        assertFalse(viewModel.showPrompt.value)
    }

    @Test
    fun `the prompt shows while the access is off`() = scope.runTest {
        assertTrue(viewModel().showPrompt.value)
    }

    @Test
    fun `the prompt stays away with the access on`() = scope.runTest {
        every { access.isGranted() } returns true

        assertFalse(viewModel().showPrompt.value)
    }

    @Test
    fun `the prompt stays away below Android 14`() = scope.runTest {
        every { access.apiLevel } returns 33

        assertFalse(viewModel().showPrompt.value)
    }

    @Test
    fun `Not now is remembered and takes the prompt away`() = scope.runTest {
        val viewModel = viewModel()

        viewModel.dismiss()

        assertTrue(dismissed.value)
        assertFalse(viewModel.showPrompt.value)
    }

    @Test
    fun `a remembered Not now keeps the prompt away on the next start`() = scope.runTest {
        dismissed.value = true

        assertFalse(viewModel().showPrompt.value)
    }

    @Test
    fun `the prompt goes on the resume after the access was switched on`() = scope.runTest {
        val viewModel = viewModel()
        assertTrue(viewModel.showPrompt.value)

        every { access.isGranted() } returns true
        viewModel.refresh()

        assertFalse(viewModel.showPrompt.value)
    }

    @Test
    fun `the prompt comes back on the resume after the access was switched off`() = scope.runTest {
        every { access.isGranted() } returns true
        val viewModel = viewModel()

        every { access.isGranted() } returns false
        viewModel.refresh()

        assertTrue(viewModel.showPrompt.value)
    }

    @Test
    fun `the button opens the settings page`() = scope.runTest {
        viewModel().openSettings()

        verify { access.openSettings() }
    }
}
