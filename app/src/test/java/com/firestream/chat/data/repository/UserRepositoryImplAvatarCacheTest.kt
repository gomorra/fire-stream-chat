package com.firestream.chat.data.repository

import android.net.Uri
import com.firestream.chat.data.local.dao.UserDao
import com.firestream.chat.data.local.entity.UserEntity
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirebaseStorageSource
import com.firestream.chat.data.remote.firebase.FirestoreUserSource
import com.firestream.chat.data.remote.firebase.RealtimePresenceSource
import com.firestream.chat.data.util.ProfileImageManager
import com.firestream.chat.domain.model.User
import io.mockk.Ordering
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class UserRepositoryImplAvatarCacheTest {

    private val userDao = mockk<UserDao>()
    private val userSource = mockk<FirestoreUserSource>()
    private val authSource = mockk<FirebaseAuthSource>()
    private val storageSource = mockk<FirebaseStorageSource>()
    private val presenceSource = mockk<RealtimePresenceSource>()
    private val profileImageManager = mockk<ProfileImageManager>()

    private lateinit var repository: UserRepositoryImpl

    private val testUser = User(
        uid = "uid1",
        phoneNumber = "+1234567890",
        displayName = "Test User",
        avatarUrl = "https://firebase.com/avatar1.jpg"
    )

    private val testEntity = UserEntity.fromDomain(testUser)

    @Before
    fun setUp() {
        repository = UserRepositoryImpl(
            userDao, userSource, authSource, storageSource,
            presenceSource, profileImageManager
        )
    }

    // --- observeUser: avatar download triggers ---

    @Test
    fun `observeUser downloads avatar when URL changed`() = runTest {
        val existingEntity = testEntity.copy(
            cachedAvatarUrl = "https://firebase.com/old-avatar.jpg",
            localAvatarPath = "/old/path.jpg"
        )
        val downloadedFile = mockk<File> { every { absolutePath } returns "/new/path.jpg" }

        every { userSource.observeUser("uid1") } returns flowOf(testUser)
        every { presenceSource.observeOnlineStatus("uid1") } returns flowOf(false)
        coEvery { userDao.getUserById("uid1") } returnsMany listOf(existingEntity, existingEntity.copy(localAvatarPath = "/new/path.jpg"))
        coEvery { userDao.insertUser(any()) } just Runs
        coEvery { profileImageManager.downloadAvatar("uid1", testUser.avatarUrl!!) } returns downloadedFile
        coEvery { userDao.updateAvatarCache("uid1", testUser.avatarUrl, "/new/path.jpg") } just Runs

        val result = repository.observeUser("uid1").first()

        coVerify(exactly = 1) { profileImageManager.downloadAvatar("uid1", testUser.avatarUrl!!) }
        coVerify(exactly = 1) { userDao.updateAvatarCache("uid1", testUser.avatarUrl, "/new/path.jpg") }
        assertEquals("/new/path.jpg", result.localAvatarPath)
    }

    @Test
    fun `observeUser skips download when URL unchanged and file exists`() = runTest {
        val existingEntity = testEntity.copy(
            cachedAvatarUrl = testUser.avatarUrl,
            localAvatarPath = "/cached/path.jpg"
        )

        every { userSource.observeUser("uid1") } returns flowOf(testUser)
        every { presenceSource.observeOnlineStatus("uid1") } returns flowOf(false)
        coEvery { userDao.getUserById("uid1") } returns existingEntity
        coEvery { userDao.insertUser(any()) } just Runs
        every { profileImageManager.fileExists("uid1") } returns true

        repository.observeUser("uid1").first()

        coVerify(exactly = 0) { profileImageManager.downloadAvatar(any(), any()) }
    }

    @Test
    fun `observeUser re-downloads when file is missing`() = runTest {
        val existingEntity = testEntity.copy(
            cachedAvatarUrl = testUser.avatarUrl,
            localAvatarPath = "/cached/path.jpg"
        )
        val downloadedFile = mockk<File> { every { absolutePath } returns "/cached/path.jpg" }

        every { userSource.observeUser("uid1") } returns flowOf(testUser)
        every { presenceSource.observeOnlineStatus("uid1") } returns flowOf(false)
        coEvery { userDao.getUserById("uid1") } returns existingEntity
        coEvery { userDao.insertUser(any()) } just Runs
        every { profileImageManager.fileExists("uid1") } returns false
        coEvery { profileImageManager.downloadAvatar("uid1", testUser.avatarUrl!!) } returns downloadedFile
        coEvery { userDao.updateAvatarCache("uid1", testUser.avatarUrl, "/cached/path.jpg") } just Runs

        repository.observeUser("uid1").first()

        coVerify(exactly = 1) { profileImageManager.downloadAvatar("uid1", testUser.avatarUrl!!) }
        coVerify(exactly = 1) { userDao.updateAvatarCache("uid1", testUser.avatarUrl, "/cached/path.jpg") }
    }

    @Test
    fun `observeUser downloads avatar for new user with no cache`() = runTest {
        val downloadedFile = mockk<File> { every { absolutePath } returns "/new/path.jpg" }

        every { userSource.observeUser("uid1") } returns flowOf(testUser)
        every { presenceSource.observeOnlineStatus("uid1") } returns flowOf(false)
        coEvery { userDao.getUserById("uid1") } returnsMany listOf(null, testEntity.copy(localAvatarPath = "/new/path.jpg"))
        coEvery { userDao.insertUser(any()) } just Runs
        coEvery { profileImageManager.downloadAvatar("uid1", testUser.avatarUrl!!) } returns downloadedFile
        coEvery { userDao.updateAvatarCache("uid1", testUser.avatarUrl, "/new/path.jpg") } just Runs

        val result = repository.observeUser("uid1").first()

        coVerify(exactly = 1) { profileImageManager.downloadAvatar("uid1", testUser.avatarUrl!!) }
        assertEquals("/new/path.jpg", result.localAvatarPath)
    }

    @Test
    fun `observeUser deletes avatar when URL becomes null`() = runTest {
        val userNoAvatar = testUser.copy(avatarUrl = null)
        val existingEntity = testEntity.copy(
            avatarUrl = null,
            cachedAvatarUrl = "https://firebase.com/old.jpg",
            localAvatarPath = "/old/path.jpg"
        )

        every { userSource.observeUser("uid1") } returns flowOf(userNoAvatar)
        every { presenceSource.observeOnlineStatus("uid1") } returns flowOf(false)
        coEvery { userDao.getUserById("uid1") } returns existingEntity
        coEvery { userDao.insertUser(any()) } just Runs
        every { profileImageManager.deleteAvatar("uid1") } just Runs
        coEvery { userDao.updateAvatarCache("uid1", null, null) } just Runs

        repository.observeUser("uid1").first()

        verify(exactly = 1) { profileImageManager.deleteAvatar("uid1") }
        coVerify(exactly = 1) { userDao.updateAvatarCache("uid1", null, null) }
    }

    @Test
    fun `observeUser swallows download failure silently`() = runTest {
        every { userSource.observeUser("uid1") } returns flowOf(testUser)
        every { presenceSource.observeOnlineStatus("uid1") } returns flowOf(false)
        coEvery { userDao.getUserById("uid1") } returns null
        coEvery { userDao.insertUser(any()) } just Runs
        coEvery { profileImageManager.downloadAvatar("uid1", testUser.avatarUrl!!) } throws RuntimeException("Network error")

        // Should not throw
        val result = repository.observeUser("uid1").first()
        assertNull(result.localAvatarPath)
    }

    @Test
    fun `observeUser preserves cache fields during Room insert`() = runTest {
        val existingEntity = testEntity.copy(
            cachedAvatarUrl = testUser.avatarUrl,
            localAvatarPath = "/cached/path.jpg"
        )

        every { userSource.observeUser("uid1") } returns flowOf(testUser)
        every { presenceSource.observeOnlineStatus("uid1") } returns flowOf(false)
        coEvery { userDao.getUserById("uid1") } returns existingEntity
        coEvery { userDao.insertUser(any()) } just Runs
        every { profileImageManager.fileExists("uid1") } returns true

        repository.observeUser("uid1").first()

        // Verify insert preserves existing cache fields
        coVerify {
            userDao.insertUser(match { entity ->
                entity.cachedAvatarUrl == testUser.avatarUrl &&
                    entity.localAvatarPath == "/cached/path.jpg"
            })
        }
    }

    // --- uploadAvatar ---
    // Note: repository.uploadAvatar now takes a URI string and calls Uri.parse()
    // internally, so these tests mock Uri.parse to return a mock Uri that is
    // then handed to the sources (whose signatures still take Uri).

    @Test
    fun `uploadAvatar saves local copy before uploading`() = runTest {
        val uriString = "content://media/picker/1"
        val parsedUri = mockk<Uri>()
        val localFile = mockk<File> { every { absolutePath } returns "/local/avatar.jpg" }

        mockkStatic(Uri::class)
        every { Uri.parse(uriString) } returns parsedUri
        every { authSource.currentUserId } returns "uid1"
        coEvery { profileImageManager.saveLocalCopy("uid1", parsedUri) } returns localFile
        coEvery { storageSource.uploadAvatar("uid1", parsedUri) } returns "https://firebase.com/new.jpg"
        coEvery { userSource.updateProfile("uid1", any()) } just Runs
        coEvery { userDao.updateAvatarCache("uid1", "https://firebase.com/new.jpg", "/local/avatar.jpg") } just Runs

        val result = repository.uploadAvatar(uriString)

        assertTrue(result.isSuccess)
        coVerify(ordering = Ordering.ORDERED) {
            profileImageManager.saveLocalCopy("uid1", parsedUri)
            storageSource.uploadAvatar("uid1", parsedUri)
        }
        coVerify { userDao.updateAvatarCache("uid1", "https://firebase.com/new.jpg", "/local/avatar.jpg") }
        unmockkStatic(Uri::class)
    }

    @Test
    fun `uploadAvatar returns failure when not authenticated`() = runTest {
        every { authSource.currentUserId } returns null

        val result = repository.uploadAvatar("content://unused")

        assertTrue(result.isFailure)
    }

    @Test
    fun `uploadAvatar returns failure when upload throws`() = runTest {
        val uriString = "content://media/picker/1"
        val parsedUri = mockk<Uri>()
        val localFile = mockk<File> { every { absolutePath } returns "/local/avatar.jpg" }

        mockkStatic(Uri::class)
        every { Uri.parse(uriString) } returns parsedUri
        every { authSource.currentUserId } returns "uid1"
        coEvery { profileImageManager.saveLocalCopy("uid1", parsedUri) } returns localFile
        coEvery { storageSource.uploadAvatar("uid1", parsedUri) } throws RuntimeException("Upload failed")

        val result = repository.uploadAvatar(uriString)

        assertTrue(result.isFailure)
        unmockkStatic(Uri::class)
    }
}
