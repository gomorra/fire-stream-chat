package com.firestream.chat.ui.auth

import com.firestream.chat.data.remote.auth.FirebasePhoneAuth
import com.firestream.chat.domain.model.RemoteBackupSummary
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.BackupRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelRestoreTest {

    private val testDispatcher = StandardTestDispatcher()
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val firebasePhoneAuth = mockk<FirebasePhoneAuth>(relaxed = true)
    private val backupRepository = mockk<BackupRepository>(relaxed = true)

    private val existingUser = User(uid = "u1", displayName = "Alice", phoneNumber = "+15551234567")
    private val summary = RemoteBackupSummary(createdAt = 1_000L, dbCipherSizeBytes = 2048L, legacyMediaComplete = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { authRepository.isLoggedIn } returns false
        every { backupRepository.isSupported } returns true
        coEvery { backupRepository.isRestorePromptResolved() } returns false
        coEvery { backupRepository.hasLocalMessages() } returns false
        coEvery { backupRepository.fetchRemoteBackupSummary() } returns Result.success(summary)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `verifyOtp offers restore for existing user with empty DB and remote backup`() = runTest {
        coEvery { authRepository.verifyOtp("vid", "123456") } returns Result.success(existingUser)
        val vm = AuthViewModel(authRepository, firebasePhoneAuth, backupRepository)
        advanceUntilIdle()

        vm.verifyOtp("vid", "123456")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isNewUser)
        assertTrue(vm.uiState.value.shouldOfferRestore)
    }

    @Test
    fun `checkLoginStatus still offers restore if process died before restore resolved`() = runTest {
        every { authRepository.isLoggedIn } returns true
        coEvery { authRepository.getCurrentUser() } returns Result.success(existingUser)

        val vm = AuthViewModel(authRepository, firebasePhoneAuth, backupRepository)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isLoggedIn)
        assertTrue(vm.uiState.value.shouldOfferRestore)
    }

    @Test
    fun `restore prompt is skipped when local messages exist, already resolved, new user, no backup, or unsupported`() = runTest {
        coEvery { authRepository.verifyOtp("vid", "123456") } returns Result.success(existingUser)

        // 1. Local messages already present -> marks resolved & does not offer
        coEvery { backupRepository.hasLocalMessages() } returns true
        val vm1 = AuthViewModel(authRepository, firebasePhoneAuth, backupRepository)
        vm1.verifyOtp("vid", "123456")
        advanceUntilIdle()
        assertFalse(vm1.uiState.value.shouldOfferRestore)
        coVerify(atLeast = 1) { backupRepository.markRestorePromptResolved() }

        // 2. Prompt already resolved (e.g. skipped) -> does not fetch manifest
        coEvery { backupRepository.hasLocalMessages() } returns false
        coEvery { backupRepository.isRestorePromptResolved() } returns true
        val vm2 = AuthViewModel(authRepository, firebasePhoneAuth, backupRepository)
        vm2.verifyOtp("vid", "123456")
        advanceUntilIdle()
        assertFalse(vm2.uiState.value.shouldOfferRestore)

        // 3. No remote backup -> does not offer
        coEvery { backupRepository.isRestorePromptResolved() } returns false
        coEvery { backupRepository.fetchRemoteBackupSummary() } returns Result.success(null)
        val vm3 = AuthViewModel(authRepository, firebasePhoneAuth, backupRepository)
        vm3.verifyOtp("vid", "123456")
        advanceUntilIdle()
        assertFalse(vm3.uiState.value.shouldOfferRestore)

        // 4. Backend unsupported (PocketBase) -> does not offer
        every { backupRepository.isSupported } returns false
        coEvery { backupRepository.fetchRemoteBackupSummary() } returns Result.success(summary)
        val vm4 = AuthViewModel(authRepository, firebasePhoneAuth, backupRepository)
        vm4.verifyOtp("vid", "123456")
        advanceUntilIdle()
        assertFalse(vm4.uiState.value.shouldOfferRestore)
    }

    @Test
    fun `createProfile marks restore prompt resolved`() = runTest {
        coEvery { authRepository.createUserProfile("Alice", null) } returns Result.success(existingUser)
        val vm = AuthViewModel(authRepository, firebasePhoneAuth, backupRepository)
        advanceUntilIdle()

        vm.createProfile("Alice")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isLoggedIn)
        coVerify(exactly = 1) { backupRepository.markRestorePromptResolved() }
    }
}
