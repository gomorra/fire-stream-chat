package com.firestream.chat.data.repository

import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.SignalDatabase
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.local.dao.UserDao
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.StickerPackSource
import com.firestream.chat.data.sticker.StickerLibrarySync
import com.firestream.chat.data.worker.StickerSyncScheduler
import com.google.firebase.messaging.FirebaseMessaging
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * Locks in the contract added when Signal Protocol tables moved to a dedicated
 * [SignalDatabase]: [AuthRepositoryImpl.signOut] must clear BOTH databases before tearing
 * down the auth session, otherwise the next user's first sign-in inherits stale Signal
 * keys from the previous account.
 *
 * The sign-out runs inside the sticker restore's fence, which also stops the
 * sticker sync and clears the recents of the user who is leaving.
 */
class AuthRepositoryImplSignOutTest {

    private val authSource = mockk<AuthSource>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val signalDatabase = mockk<SignalDatabase>(relaxed = true)
    private val userDao = mockk<UserDao>(relaxed = true)
    private val signalManager = mockk<SignalManager>(relaxed = true)
    private val firebaseMessaging = mockk<FirebaseMessaging>(relaxed = true)
    private val stickerSyncScheduler = mockk<StickerSyncScheduler>(relaxed = true)
    private val preferences = mockk<PreferencesDataStore>(relaxed = true)
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val stickerLibrarySync = StickerLibrarySync(
        mockk<StickerDao>(relaxed = true),
        mockk<StickerPackSource>(relaxed = true),
        authSource,
        stickerSyncScheduler,
        preferences,
        syncScope,
    )

    private val repository = AuthRepositoryImpl(
        authSource, database, signalDatabase, userDao, signalManager, firebaseMessaging, stickerLibrarySync
    )

    @After
    fun tearDown() {
        syncScope.cancel()
    }

    @Test
    fun `signOut clears both databases and then signs out`() = runTest {
        repository.signOut()

        coVerifyOrder {
            database.clearAllTables()
            signalDatabase.clearAllTables()
            authSource.signOut()
        }
    }

    @Test
    fun `signOut clears the SignalDatabase exactly once`() = runTest {
        repository.signOut()

        coVerify(exactly = 1) { signalDatabase.clearAllTables() }
    }

    @Test
    fun `signOut stops the sticker sync before the tables are cleared, and forgets the sticker recents after`() = runTest {
        repository.signOut()

        coVerifyOrder {
            stickerSyncScheduler.cancel()
            database.clearAllTables()
            authSource.signOut()
            preferences.clearRecentStickers()
        }
    }
}
