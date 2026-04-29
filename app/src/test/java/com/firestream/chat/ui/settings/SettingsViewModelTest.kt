package com.firestream.chat.ui.settings

import android.content.Context
import com.firestream.chat.data.local.AppTheme
import com.firestream.chat.data.local.AutoDownloadOption
import com.firestream.chat.data.local.DictationLanguage
import com.firestream.chat.data.local.NotificationSound
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.model.AppUpdate
import com.firestream.chat.domain.model.UpdateCheckResult
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AppUpdateRepository
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.DownloadProgress
import com.firestream.chat.domain.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var authRepository: AuthRepository
    private lateinit var userRepository: UserRepository
    private lateinit var preferencesDataStore: PreferencesDataStore
    private lateinit var appUpdateRepository: AppUpdateRepository
    private lateinit var appContext: Context
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
        appUpdateRepository = mockk(relaxed = true)
        appContext = mockk(relaxed = true)

        stubDefaultPreferences()

        every { authRepository.currentUserId } returns "user1"
        every { userRepository.observeUser("user1") } returns flowOf(testUser)

        viewModel = SettingsViewModel(
            authRepository = authRepository,
            userRepository = userRepository,
            preferencesDataStore = preferencesDataStore,
            appUpdateRepository = appUpdateRepository,
            appContext = appContext
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stubDefaultPreferences() {
        every { preferencesDataStore.appThemeFlow } returns flowOf(AppTheme.SYSTEM)
        every { preferencesDataStore.readReceiptsFlow } returns flowOf(true)
        every { preferencesDataStore.lastSeenVisibleFlow } returns flowOf(true)
        every { preferencesDataStore.screenSecurityFlow } returns flowOf(false)
        every { preferencesDataStore.e2eEncryptionEnabledFlow } returns flowOf(true)
        every { preferencesDataStore.messageNotificationsFlow } returns flowOf(true)
        every { preferencesDataStore.groupNotificationsFlow } returns flowOf(true)
        every { preferencesDataStore.mentionOnlyNotificationsFlow } returns flowOf(false)
        every { preferencesDataStore.notificationSoundFlow } returns flowOf(NotificationSound.DEFAULT)
        every { preferencesDataStore.vibrationFlow } returns flowOf(true)
        every { preferencesDataStore.autoDownloadFlow } returns flowOf(AutoDownloadOption.WIFI_ONLY)
        every { preferencesDataStore.sendImagesFullQualityFlow } returns flowOf(false)
        every { preferencesDataStore.dictationLanguageFlow } returns flowOf(DictationLanguage.GERMAN)
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    @Test
    fun `init loads current user`() = runTest {
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.currentUser)
        assertEquals("Alice", state.currentUser?.displayName)
        assertFalse(state.isLoading)
    }

    @Test
    fun `init loads all preferences into state`() = runTest {
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(AppTheme.SYSTEM, state.appTheme)
        assertTrue(state.readReceipts)
        assertTrue(state.lastSeenVisible)
        assertFalse(state.screenSecurity)
        assertTrue(state.messageNotifications)
        assertTrue(state.groupNotifications)
        assertFalse(state.mentionOnlyNotifications)
        assertEquals(NotificationSound.DEFAULT, state.notificationSound)
        assertTrue(state.vibration)
        assertEquals(AutoDownloadOption.WIFI_ONLY, state.autoDownload)
    }

    @Test
    fun `init with no current user id skips user load`() = runTest {
        every { authRepository.currentUserId } returns null
        val vm = SettingsViewModel(authRepository, userRepository, preferencesDataStore, appUpdateRepository, appContext)

        advanceUntilIdle()

        assertNull(vm.uiState.value.currentUser)
    }

    @Test
    fun `init sets error state when user flow throws`() = runTest {
        every { userRepository.observeUser("user1") } returns flow { throw RuntimeException("network error") }
        val vm = SettingsViewModel(authRepository, userRepository, preferencesDataStore, appUpdateRepository, appContext)

        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("network error", state.error?.message)
        assertFalse(state.isLoading)
    }

    @Test
    fun `init reflects non-default preference values`() = runTest {
        every { preferencesDataStore.appThemeFlow } returns flowOf(AppTheme.DARK)
        every { preferencesDataStore.readReceiptsFlow } returns flowOf(false)
        every { preferencesDataStore.vibrationFlow } returns flowOf(false)
        every { preferencesDataStore.notificationSoundFlow } returns flowOf(NotificationSound.SILENT)
        every { preferencesDataStore.autoDownloadFlow } returns flowOf(AutoDownloadOption.NEVER)
        val vm = SettingsViewModel(authRepository, userRepository, preferencesDataStore, appUpdateRepository, appContext)

        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(AppTheme.DARK, state.appTheme)
        assertFalse(state.readReceipts)
        assertFalse(state.vibration)
        assertEquals(NotificationSound.SILENT, state.notificationSound)
        assertEquals(AutoDownloadOption.NEVER, state.autoDownload)
    }

    // ── Theme ─────────────────────────────────────────────────────────────────

    @Test
    fun `setTheme DARK calls datastore`() = runTest {
        coEvery { preferencesDataStore.setAppTheme(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setTheme(AppTheme.DARK)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setAppTheme(AppTheme.DARK) }
    }

    @Test
    fun `setTheme LIGHT calls datastore`() = runTest {
        coEvery { preferencesDataStore.setAppTheme(AppTheme.LIGHT) } returns Unit
        advanceUntilIdle()

        viewModel.setTheme(AppTheme.LIGHT)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setAppTheme(AppTheme.LIGHT) }
    }

    @Test
    fun `setTheme SYSTEM calls datastore`() = runTest {
        coEvery { preferencesDataStore.setAppTheme(AppTheme.SYSTEM) } returns Unit
        advanceUntilIdle()

        viewModel.setTheme(AppTheme.SYSTEM)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setAppTheme(AppTheme.SYSTEM) }
    }

    // ── Privacy ───────────────────────────────────────────────────────────────

    @Test
    fun `setReadReceipts true calls datastore and userRepository`() = runTest {
        coEvery { preferencesDataStore.setReadReceipts(any()) } returns Unit
        coEvery { userRepository.updateReadReceipts(any()) } returns Result.success(Unit)
        advanceUntilIdle()

        viewModel.setReadReceipts(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setReadReceipts(true) }
        coVerify(exactly = 1) { userRepository.updateReadReceipts(true) }
    }

    @Test
    fun `setReadReceipts false calls datastore and userRepository`() = runTest {
        coEvery { preferencesDataStore.setReadReceipts(any()) } returns Unit
        coEvery { userRepository.updateReadReceipts(any()) } returns Result.success(Unit)
        advanceUntilIdle()

        viewModel.setReadReceipts(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setReadReceipts(false) }
        coVerify(exactly = 1) { userRepository.updateReadReceipts(false) }
    }

    @Test
    fun `setLastSeenVisible true calls datastore`() = runTest {
        coEvery { preferencesDataStore.setLastSeenVisible(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setLastSeenVisible(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setLastSeenVisible(true) }
    }

    @Test
    fun `setLastSeenVisible false calls datastore`() = runTest {
        coEvery { preferencesDataStore.setLastSeenVisible(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setLastSeenVisible(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setLastSeenVisible(false) }
    }

    @Test
    fun `setScreenSecurity enables calls datastore`() = runTest {
        coEvery { preferencesDataStore.setScreenSecurity(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setScreenSecurity(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setScreenSecurity(true) }
    }

    @Test
    fun `setScreenSecurity disables calls datastore`() = runTest {
        coEvery { preferencesDataStore.setScreenSecurity(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setScreenSecurity(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setScreenSecurity(false) }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    @Test
    fun `setMessageNotifications false calls datastore`() = runTest {
        coEvery { preferencesDataStore.setMessageNotifications(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setMessageNotifications(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setMessageNotifications(false) }
    }

    @Test
    fun `setMessageNotifications true calls datastore`() = runTest {
        coEvery { preferencesDataStore.setMessageNotifications(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setMessageNotifications(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setMessageNotifications(true) }
    }

    @Test
    fun `setGroupNotifications false calls datastore`() = runTest {
        coEvery { preferencesDataStore.setGroupNotifications(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setGroupNotifications(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setGroupNotifications(false) }
    }

    @Test
    fun `setGroupNotifications true calls datastore`() = runTest {
        coEvery { preferencesDataStore.setGroupNotifications(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setGroupNotifications(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setGroupNotifications(true) }
    }

    @Test
    fun `setMentionOnlyNotifications enables calls datastore`() = runTest {
        coEvery { preferencesDataStore.setMentionOnlyNotifications(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setMentionOnlyNotifications(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setMentionOnlyNotifications(true) }
    }

    @Test
    fun `setMentionOnlyNotifications disables calls datastore`() = runTest {
        coEvery { preferencesDataStore.setMentionOnlyNotifications(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setMentionOnlyNotifications(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setMentionOnlyNotifications(false) }
    }

    @Test
    fun `setNotificationSound SILENT calls datastore`() = runTest {
        coEvery { preferencesDataStore.setNotificationSound(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setNotificationSound(NotificationSound.SILENT)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setNotificationSound(NotificationSound.SILENT) }
    }

    @Test
    fun `setNotificationSound DEFAULT calls datastore`() = runTest {
        coEvery { preferencesDataStore.setNotificationSound(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setNotificationSound(NotificationSound.DEFAULT)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setNotificationSound(NotificationSound.DEFAULT) }
    }

    @Test
    fun `setVibration false calls datastore`() = runTest {
        coEvery { preferencesDataStore.setVibration(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setVibration(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setVibration(false) }
    }

    @Test
    fun `setVibration true calls datastore`() = runTest {
        coEvery { preferencesDataStore.setVibration(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setVibration(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setVibration(true) }
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    @Test
    fun `init reflects persisted dictation language`() = runTest {
        every { preferencesDataStore.dictationLanguageFlow } returns flowOf(DictationLanguage.ENGLISH)
        val vm = SettingsViewModel(authRepository, userRepository, preferencesDataStore, appUpdateRepository, appContext)

        advanceUntilIdle()

        assertEquals(DictationLanguage.ENGLISH, vm.uiState.value.dictationLanguage)
    }

    @Test
    fun `setDictationLanguage ENGLISH calls datastore`() = runTest {
        coEvery { preferencesDataStore.setDictationLanguage(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setDictationLanguage(DictationLanguage.ENGLISH)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setDictationLanguage(DictationLanguage.ENGLISH) }
    }

    @Test
    fun `setDictationLanguage GERMAN calls datastore`() = runTest {
        coEvery { preferencesDataStore.setDictationLanguage(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setDictationLanguage(DictationLanguage.GERMAN)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setDictationLanguage(DictationLanguage.GERMAN) }
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    @Test
    fun `setAutoDownload ALWAYS calls datastore`() = runTest {
        coEvery { preferencesDataStore.setAutoDownload(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setAutoDownload(AutoDownloadOption.ALWAYS)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setAutoDownload(AutoDownloadOption.ALWAYS) }
    }

    @Test
    fun `setAutoDownload NEVER calls datastore`() = runTest {
        coEvery { preferencesDataStore.setAutoDownload(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setAutoDownload(AutoDownloadOption.NEVER)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setAutoDownload(AutoDownloadOption.NEVER) }
    }

    @Test
    fun `setAutoDownload WIFI_ONLY calls datastore`() = runTest {
        coEvery { preferencesDataStore.setAutoDownload(any()) } returns Unit
        advanceUntilIdle()

        viewModel.setAutoDownload(AutoDownloadOption.WIFI_ONLY)
        advanceUntilIdle()

        coVerify(exactly = 1) { preferencesDataStore.setAutoDownload(AutoDownloadOption.WIFI_ONLY) }
    }

    @Test
    fun `clearCache deletes cacheDir and recalculates size`() = runTest {
        val mockCacheDir = mockk<File>(relaxed = true)
        every { appContext.cacheDir } returns mockCacheDir
        every { mockCacheDir.listFiles() } returns null
        advanceUntilIdle()

        viewModel.clearCache()
        advanceUntilIdle()

        coVerify { mockCacheDir.deleteRecursively() }
        coVerify { mockCacheDir.mkdirs() }
        assertEquals(0L, viewModel.uiState.value.cacheSize)
    }

    @Test
    fun `clearCache resets cacheSize to zero when dir is empty`() = runTest {
        val mockCacheDir = mockk<File>(relaxed = true)
        every { appContext.cacheDir } returns mockCacheDir
        every { mockCacheDir.listFiles() } returns emptyArray()
        advanceUntilIdle()

        viewModel.clearCache()
        advanceUntilIdle()

        assertEquals(0L, viewModel.uiState.value.cacheSize)
    }

    @Test
    fun `clearCache does not throw when deleteRecursively fails`() = runTest {
        val mockCacheDir = mockk<File>(relaxed = true)
        every { appContext.cacheDir } returns mockCacheDir
        every { mockCacheDir.deleteRecursively() } throws RuntimeException("permission denied")
        advanceUntilIdle()

        // Should not propagate the exception
        viewModel.clearCache()
        advanceUntilIdle()
    }

    // ── Sign out ──────────────────────────────────────────────────────────────

    @Test
    fun `signOut calls authRepository signOut`() = runTest {
        coEvery { authRepository.signOut() } returns Unit
        advanceUntilIdle()

        viewModel.signOut()
        advanceUntilIdle()

        coVerify(exactly = 1) { authRepository.signOut() }
    }

    // ── App update ────────────────────────────────────────────────────────────

    private fun manifest(versionCode: Int = 999, versionName: String = "9.9.9") = AppUpdate(
        versionCode = versionCode,
        versionName = versionName,
        apkUrl = "https://example.com/x.apk",
        sha256 = "abc",
        minSupportedVersionCode = 1,
        releaseNotes = "Notes",
        publishedAt = "",
        mandatory = false
    )

    @Test
    fun `checkForUpdate sets Available when repo returns newer version`() = runTest {
        val update = manifest()
        coEvery { appUpdateRepository.checkForUpdate() } returns Result.success(UpdateCheckResult.Available(update))
        advanceUntilIdle()

        viewModel.checkForUpdate()
        advanceUntilIdle()

        assertEquals(UpdateUiState.Available(update), viewModel.uiState.value.update)
    }

    @Test
    fun `checkForUpdate sets UpToDate when repo says no update`() = runTest {
        coEvery { appUpdateRepository.checkForUpdate() } returns Result.success(UpdateCheckResult.UpToDate)
        advanceUntilIdle()

        viewModel.checkForUpdate()
        advanceUntilIdle()

        assertEquals(UpdateUiState.UpToDate, viewModel.uiState.value.update)
    }

    @Test
    fun `checkForUpdate sets Failed when repo returns failure`() = runTest {
        coEvery { appUpdateRepository.checkForUpdate() } returns Result.failure(RuntimeException("boom"))
        advanceUntilIdle()

        viewModel.checkForUpdate()
        advanceUntilIdle()

        val state = viewModel.uiState.value.update
        assertTrue(state is UpdateUiState.Failed)
    }

    @Test
    fun `downloadAndInstall progresses through Downloading then triggers install`() = runTest {
        val update = manifest()
        val tmpFile = java.io.File.createTempFile("test", ".apk")
        coEvery { appUpdateRepository.downloadUpdate(update) } returns kotlinx.coroutines.flow.flowOf(
            DownloadProgress.InProgress(50, 100),
            DownloadProgress.Done(tmpFile)
        )
        coEvery { appUpdateRepository.installUpdate(tmpFile) } returns Result.success(Unit)
        advanceUntilIdle()

        viewModel.downloadAndInstall(update)
        advanceUntilIdle()

        coVerify(exactly = 1) { appUpdateRepository.installUpdate(tmpFile) }
        assertEquals(UpdateUiState.Idle, viewModel.uiState.value.update)
        tmpFile.delete()
    }

    @Test
    fun `downloadAndInstall sets Failed when downloader emits Failed`() = runTest {
        val update = manifest()
        coEvery { appUpdateRepository.downloadUpdate(update) } returns kotlinx.coroutines.flow.flowOf(
            DownloadProgress.Failed("checksum mismatch")
        )
        advanceUntilIdle()

        viewModel.downloadAndInstall(update)
        advanceUntilIdle()

        val state = viewModel.uiState.value.update
        assertTrue(state is UpdateUiState.Failed)
        assertEquals("checksum mismatch", (state as UpdateUiState.Failed).message)
    }
}
