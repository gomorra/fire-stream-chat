package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.UserDao
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirebaseStorageSource
import com.firestream.chat.data.remote.firebase.FirestoreUserSource
import com.firestream.chat.data.remote.firebase.RealtimePresenceSource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UserRepositoryImplPresenceTest {

    private val userDao = mockk<UserDao>()
    private val userSource = mockk<FirestoreUserSource>()
    private val authSource = mockk<FirebaseAuthSource>()
    private val storageSource = mockk<FirebaseStorageSource>()
    private val presenceSource = mockk<RealtimePresenceSource>()

    private lateinit var repository: UserRepositoryImpl

    @Before
    fun setUp() {
        every { presenceSource.startPresence(any()) } just Runs
        coEvery { presenceSource.goOffline(any()) } just Runs

        repository = UserRepositoryImpl(userDao, userSource, authSource, storageSource, presenceSource)
    }

    @Test
    fun `setOnlineStatus true calls startPresence`() = runTest {
        every { authSource.currentUserId } returns "uid1"

        val result = repository.setOnlineStatus(true)

        assertTrue(result.isSuccess)
        verify(exactly = 1) { presenceSource.startPresence("uid1") }
    }

    @Test
    fun `setOnlineStatus false calls goOffline`() = runTest {
        every { authSource.currentUserId } returns "uid1"

        val result = repository.setOnlineStatus(false)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { presenceSource.goOffline("uid1") }
    }

    @Test
    fun `setOnlineStatus does NOT write to Firestore directly`() = runTest {
        every { authSource.currentUserId } returns "uid1"

        repository.setOnlineStatus(true)
        repository.setOnlineStatus(false)

        // Cloud Function is the sole writer to Firestore — no direct call allowed
        coVerify(exactly = 0) { userSource.setOnlineStatus(any(), any()) }
    }

    @Test
    fun `setOnlineStatus returns failure when not authenticated`() = runTest {
        every { authSource.currentUserId } returns null

        val result = repository.setOnlineStatus(true)

        assertTrue(result.isFailure)
        verify(exactly = 0) { presenceSource.startPresence(any()) }
    }

    @Test
    fun `setOnlineStatus returns failure when presenceSource throws`() = runTest {
        every { authSource.currentUserId } returns "uid1"
        every { presenceSource.startPresence(any()) } throws RuntimeException("RTDB unavailable")

        val result = repository.setOnlineStatus(true)

        assertTrue(result.isFailure)
    }
}
