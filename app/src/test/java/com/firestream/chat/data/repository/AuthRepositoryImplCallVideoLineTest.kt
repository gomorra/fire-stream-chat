package com.firestream.chat.data.repository

import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.SignalDatabase
import com.firestream.chat.data.local.dao.UserDao
import com.firestream.chat.data.remote.source.AuthSource
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * A caller offers a video line only to a user whose document says their app takes one. This app
 * says so when it starts and when an existing user signs in, because a start while signed out
 * writes nothing.
 */
class AuthRepositoryImplCallVideoLineTest {

    private val authSource = mockk<AuthSource>(relaxed = true)
    private val firebaseMessaging = mockk<FirebaseMessaging>(relaxed = true)

    private val repository = AuthRepositoryImpl(
        authSource,
        mockk<AppDatabase>(relaxed = true),
        mockk<SignalDatabase>(relaxed = true),
        mockk<UserDao>(relaxed = true),
        mockk<SignalManager>(relaxed = true),
        firebaseMessaging,
        mockk(relaxed = true),
    )

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "me"
        // A token that is there at once, so `await()` returns without a listener.
        val token = mockk<Task<String>>()
        every { token.isComplete } returns true
        every { token.isCanceled } returns false
        every { token.exception } returns null
        every { token.result } returns "fcm-token"
        every { firebaseMessaging.token } returns token
    }

    @Test
    fun `the announcement is written for the signed-in user`() = runTest {
        val result = repository.announceCallVideoLine()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { authSource.announceCallVideoLine("me") }
    }

    @Test
    fun `signed out, nothing is written`() = runTest {
        every { authSource.currentUserId } returns null

        val result = repository.announceCallVideoLine()

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { authSource.announceCallVideoLine(any()) }
    }

    @Test
    fun `a write that fails is reported, not thrown`() = runTest {
        coEvery { authSource.announceCallVideoLine("me") } throws IOException("unavailable")

        assertTrue(repository.announceCallVideoLine().isFailure)
    }

    @Test
    fun `signing in as an existing user announces`() = runTest {
        coEvery { authSource.signInWithVerification("verification", "123456") } returns "me"
        coEvery { authSource.getUserDocument("me") } returns mapOf("displayName" to "Me")

        assertTrue(repository.verifyOtp("verification", "123456").isSuccess)

        // Launched, not awaited: the sign-in does not wait for the write.
        coVerify(timeout = 5_000, exactly = 1) { authSource.announceCallVideoLine("me") }
    }

    // No user document yet: the write would fail. Creating the profile announces.
    @Test
    fun `signing in as a new user does not announce`() = runTest {
        coEvery { authSource.signInWithVerification("verification", "123456") } returns "me"
        coEvery { authSource.getUserDocument("me") } returns null

        assertTrue(repository.verifyOtp("verification", "123456").isSuccess)

        coVerify(exactly = 0) { authSource.announceCallVideoLine(any()) }
    }

    // The source writes the field into the new user document itself.
    @Test
    fun `a new profile needs no second write`() = runTest {
        assertTrue(repository.createUserProfile("Me", avatarUrl = null).isSuccess)

        coVerify(exactly = 1) { authSource.createUserDocument("me", any(), "Me", null) }
        coVerify(exactly = 0) { authSource.announceCallVideoLine(any()) }
    }

    @Test
    fun `a failed announcement does not fail the sign-in`() = runTest {
        coEvery { authSource.signInWithVerification("verification", "123456") } returns "me"
        coEvery { authSource.getUserDocument("me") } returns mapOf("displayName" to "Me")
        coEvery { authSource.announceCallVideoLine("me") } throws IOException("unavailable")

        assertTrue(repository.verifyOtp("verification", "123456").isSuccess)
    }
}
