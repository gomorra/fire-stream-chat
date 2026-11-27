package com.firestream.chat.ui.settings

import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.BackupFrequency
import com.firestream.chat.domain.model.BackupProgress
import com.firestream.chat.domain.model.BackupStage
import com.firestream.chat.domain.model.LocalBackupStatus
import com.firestream.chat.domain.model.ManualBackupWorkState
import com.firestream.chat.domain.model.RemoteBackupSummary
import com.firestream.chat.domain.model.RestoreProgress
import com.firestream.chat.domain.model.RestoreStage
import com.firestream.chat.domain.model.WrongPassphraseException
import com.firestream.chat.domain.repository.BackupRepository
import com.firestream.chat.ui.auth.RestoreBackupViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val localStatusFlow = MutableStateFlow(LocalBackupStatus(isSupported = true))
    private val backupProgressFlow = MutableStateFlow(BackupProgress())
    private val restoreProgressFlow = MutableStateFlow(RestoreProgress())
    private val manualWorkStateFlow = MutableStateFlow(ManualBackupWorkState.IDLE)
    private lateinit var backupRepository: BackupRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        backupRepository = mockk(relaxed = true)
        every { backupRepository.isSupported } returns true
        every { backupRepository.localStatus } returns localStatusFlow
        every { backupRepository.backupProgress } returns backupProgressFlow
        every { backupRepository.restoreProgress } returns restoreProgressFlow
        every { backupRepository.manualBackupWorkState } returns manualWorkStateFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `savePassphrase rejects short passphrase and mismatch before calling repository`() = runTest {
        val vm = BackupViewModel(backupRepository, this)
        advanceUntilIdle()

        vm.openPassphraseDialog()
        vm.savePassphrase("too-short", "too-short")
        assertTrue(vm.uiState.value.passphraseError is AppError.Validation)
        assertTrue(vm.uiState.value.passphraseError!!.message.contains("12"))
        coVerify(exactly = 0) { backupRepository.configurePassphrase(any()) }

        vm.savePassphrase("valid-passphrase-123", "different-passphrase-123")
        assertTrue(vm.uiState.value.passphraseError!!.message.contains("match"))
        coVerify(exactly = 0) { backupRepository.configurePassphrase(any()) }
    }

    @Test
    fun `savePassphrase configures key on first setup and enqueues backup on passphrase rotation`() = runTest {
        coEvery { backupRepository.configurePassphrase(any()) } returns Result.success(Unit)

        val vm = BackupViewModel(backupRepository, this)
        advanceUntilIdle()

        // First setup opened via "Set backup passphrase" row -> saves key & initial schedule without running backup
        vm.openPassphraseDialog()
        vm.savePassphrase("first-strong-passphrase", "first-strong-passphrase", BackupFrequency.WEEKLY)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showPassphraseDialog)
        coVerify(exactly = 1) { backupRepository.configurePassphrase("first-strong-passphrase") }
        coVerify(exactly = 1) { backupRepository.setBackupFrequency(BackupFrequency.WEEKLY) }
        coVerify(exactly = 0) { backupRepository.enqueueManualBackup() }

        // Passphrase change when already backed up -> enqueues manual backup to commit rotation without changing schedule
        localStatusFlow.value = LocalBackupStatus(
            isSupported = true,
            isPassphraseConfigured = true,
            lastBackupAt = 1_000L,
            lastBackupLegacyMediaComplete = true,
        )
        advanceUntilIdle()

        vm.openPassphraseDialog()
        vm.savePassphrase("second-strong-passphrase", "second-strong-passphrase", BackupFrequency.DAILY)
        advanceUntilIdle()
        coVerify(exactly = 1) { backupRepository.configurePassphrase("second-strong-passphrase") }
        coVerify(exactly = 0) { backupRepository.setBackupFrequency(BackupFrequency.DAILY) }
        coVerify(exactly = 1) { backupRepository.enqueueManualBackup() }
    }

    @Test
    fun `backupNow when unconfigured opens passphrase dialog, enqueues backup on save, and surfaces completion snackbar`() = runTest {
        coEvery { backupRepository.configurePassphrase(any()) } returns Result.success(Unit)

        val vm = BackupViewModel(backupRepository, this)
        advanceUntilIdle()

        vm.backupNow()
        assertTrue(vm.uiState.value.showPassphraseDialog)
        coVerify(exactly = 0) { backupRepository.enqueueManualBackup() }

        vm.savePassphrase("setup-and-backup-now-123", "setup-and-backup-now-123", BackupFrequency.DAILY)
        advanceUntilIdle()
        coVerify(exactly = 1) { backupRepository.configurePassphrase("setup-and-backup-now-123") }
        coVerify(exactly = 1) { backupRepository.setBackupFrequency(BackupFrequency.DAILY) }
        coVerify(exactly = 1) { backupRepository.enqueueManualBackup() }

        backupProgressFlow.value = BackupProgress(stage = BackupStage.UPLOADING_DB, fraction = 0.85f)
        advanceUntilIdle()
        backupProgressFlow.value = BackupProgress(
            stage = BackupStage.COMPLETED,
            fraction = 1f,
            legacyMediaComplete = false,
        )
        advanceUntilIdle()
        assertEquals(
            "Backup saved, but some legacy media could not be backed up. Tap Back up now to retry.",
            vm.uiState.value.snackbarMessage,
        )
    }

    @Test
    fun `savePassphrase surfaces wrapped AppError on repository failure`() = runTest {
        coEvery { backupRepository.configurePassphrase(any()) } returns Result.failure(IOException("keystore failed"))
        val vm = BackupViewModel(backupRepository, this)
        advanceUntilIdle()

        vm.openPassphraseDialog()
        vm.savePassphrase("valid-passphrase-123", "valid-passphrase-123")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.showPassphraseDialog)
        assertNotNull(vm.uiState.value.passphraseError)
        assertFalse(vm.uiState.value.isSavingPassphrase)
    }

    @Test
    fun `waiting for Wi-Fi state, cancelBackup, setFrequency, and setAllowMobileData delegate to repository`() = runTest {
        val vm = BackupViewModel(backupRepository, this)
        advanceUntilIdle()

        manualWorkStateFlow.value = ManualBackupWorkState.WAITING_FOR_NETWORK
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isWaitingForNetwork)
        assertTrue(vm.uiState.value.isBackupActive)
        assertFalse(vm.uiState.value.isBackupRunning)

        backupProgressFlow.value = BackupProgress(stage = BackupStage.UPLOADING_DB, fraction = 0.6f)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isBackupRunning)
        assertFalse(vm.uiState.value.isWaitingForNetwork)
        assertEquals(0.6f, vm.uiState.value.progress.fraction)

        vm.cancelBackup()
        advanceUntilIdle()
        coVerify(exactly = 1) { backupRepository.cancelBackup() }
        assertEquals("Backup canceled.", vm.uiState.value.snackbarMessage)

        vm.setFrequency(BackupFrequency.DAILY)
        vm.setAllowMobileData(true)
        advanceUntilIdle()
        coVerify(exactly = 1) { backupRepository.setBackupFrequency(BackupFrequency.DAILY) }
        coVerify(exactly = 1) { backupRepository.setBackupOverMobileData(true) }
    }

    @Test
    fun `RestoreBackupViewModel surfaces summary retry, wrong passphrase error, skip, and success`() = runTest {
        val summary = RemoteBackupSummary(createdAt = 1_750_000_000_000L, dbCipherSizeBytes = 2_097_152L, legacyMediaComplete = true)
        coEvery { backupRepository.fetchRemoteBackupSummary() } returnsMany listOf(
            Result.failure(IOException("offline")),
            Result.success(summary),
        )
        coEvery { backupRepository.restoreBackup("bad-pass") } returns Result.failure(WrongPassphraseException())
        coEvery { backupRepository.restoreBackup("good-passphrase-123") } returns Result.success(Unit)

        val vm = RestoreBackupViewModel(backupRepository, this)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.summaryError)
        assertNull(vm.uiState.value.summary)

        vm.loadSummary()
        advanceUntilIdle()
        assertEquals(summary, vm.uiState.value.summary)
        assertNull(vm.uiState.value.summaryError)

        // Empty passphrase rejected locally
        vm.restore("")
        assertTrue(vm.uiState.value.error is AppError.Validation)
        coVerify(exactly = 0) { backupRepository.restoreBackup(any()) }

        // Wrong passphrase shows friendly error
        vm.restore("bad-pass")
        advanceUntilIdle()
        assertEquals("Incorrect passphrase. Please try again.", vm.uiState.value.error?.message)
        assertFalse(vm.uiState.value.restoreSucceeded)

        // Progress flow updates during restore
        restoreProgressFlow.value = RestoreProgress(stage = RestoreStage.DECRYPTING_DB, fraction = 0.7f)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isRestoring)

        // Reset progress to idle and run successful restore
        restoreProgressFlow.value = RestoreProgress(stage = RestoreStage.IDLE)
        advanceUntilIdle()
        vm.restore("good-passphrase-123")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.restoreSucceeded)
        assertNull(vm.uiState.value.error)

        vm.markSkipped()
        advanceUntilIdle()
        coVerify(exactly = 1) { backupRepository.markRestorePromptResolved() }
    }
}
