package com.firestream.chat.data.sticker

import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.local.entity.StickerEntity
import com.firestream.chat.data.local.entity.StickerPackEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.RemoteSticker
import com.firestream.chat.data.remote.source.RemoteStickerPack
import com.firestream.chat.data.remote.source.StickerPackChanges
import com.firestream.chat.data.remote.source.StickerPackSource
import com.firestream.chat.data.worker.StickerSyncScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The restore's own rules: when it listens, whose manifests it takes, and that
 * nothing is written for a user who has signed out. How a manifest merges into
 * the tables is `StickerDaoTest`'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StickerLibrarySyncTest {

    private val stickerDao = mockk<StickerDao>(relaxed = true)
    private val packSource = mockk<StickerPackSource> { every { isSupported } returns true }
    private val authSource = mockk<AuthSource> { every { currentUserId } returns "uid1" }
    private val scheduler = mockk<StickerSyncScheduler>(relaxed = true)
    private val preferences = mockk<PreferencesDataStore>(relaxed = true)

    private fun TestScope.sync(appScope: CoroutineScope) =
        StickerLibrarySync(stickerDao, packSource, authSource, scheduler, preferences, appScope)

    /** A scope on the test's dispatcher that the test cancels itself: the sharing coroutine never completes. */
    private fun TestScope.appScope() = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())

    private fun manifest(id: String, ownerId: String = "uid1", vararg stickerIds: String) = RemoteStickerPack(
        id = id, ownerId = ownerId, name = "Cats", publisher = null, kind = "USER", originPackId = null, importKey = null,
        sortOrder = 0, createdAt = 1L, updatedAt = 2L,
        stickers = stickerIds.map { RemoteSticker(it, "WEBP", 512, 512, false, emptyList()) },
    )

    @Test
    fun `a manifest is merged with only the stickers whose ids are hashes`() = runTest {
        val appScope = appScope()
        val valid = "a".repeat(64)
        val pack = slot<StickerPackEntity>()
        val stickers = slot<List<StickerEntity>>()
        coEvery { stickerDao.applyRemotePack(capture(pack), capture(stickers), any()) } returns true

        sync(appScope).apply("uid1", StickerPackChanges(listOf(manifest("p1", "uid1", valid, "../escape")), emptyList()))

        assertEquals("p1", pack.captured.id)
        assertEquals(listOf(valid), stickers.captured.map { it.id })
        // A merge can leave a tombstone or a changed pack behind.
        coVerify { scheduler.syncIfPending() }
        appScope.cancel()
    }

    @Test
    fun `a manifest of another owner, or with an id that is no document id, is not merged`() = runTest {
        val appScope = appScope()

        sync(appScope).apply(
            "uid1",
            StickerPackChanges(listOf(manifest("p1", ownerId = "someone"), manifest("a/b", ownerId = "uid1")), emptyList()),
        )

        coVerify(exactly = 0) { stickerDao.applyRemotePack(any(), any(), any()) }
        appScope.cancel()
    }

    @Test
    fun `a pack the backend dropped is removed through the rule for synced rows`() = runTest {
        val appScope = appScope()

        sync(appScope).apply("uid1", StickerPackChanges(emptyList(), listOf("p1", "p2")))

        coVerifyOrder {
            stickerDao.removeIfSynced("p1")
            stickerDao.removeIfSynced("p2")
        }
        appScope.cancel()
    }

    @Test
    fun `nothing is written once the listener's user is no longer signed in`() = runTest {
        val appScope = appScope()
        val sync = sync(appScope)
        val changes = StickerPackChanges(listOf(manifest("p1")), listOf("p2"))

        every { authSource.currentUserId } returns null
        sync.apply("uid1", changes)
        every { authSource.currentUserId } returns "uid2"
        sync.apply("uid1", changes)

        coVerify(exactly = 0) { stickerDao.applyRemotePack(any(), any(), any()) }
        coVerify(exactly = 0) { stickerDao.removeIfSynced(any()) }
        appScope.cancel()
    }

    @Test
    fun `the listener runs while the library is observed, for the user signed in at that moment`() = runTest {
        val appScope = appScope()
        val remote = MutableSharedFlow<StickerPackChanges>()
        every { packSource.observeOwnPacks("uid1") } returns remote
        val sync = sync(appScope)
        verify(exactly = 0) { packSource.observeOwnPacks(any()) }

        val observer = launch(UnconfinedTestDispatcher(testScheduler)) { sync.whileObserved.collect {} }
        remote.emit(StickerPackChanges(listOf(manifest("p1")), emptyList()))

        coVerify(exactly = 1) { stickerDao.applyRemotePack(match { it.id == "p1" }, any(), any()) }
        // A pack left pending by an earlier run is queued when the restore starts.
        coVerify(atLeast = 1) { scheduler.syncIfPending() }

        observer.cancel()
        testScheduler.advanceUntilIdle()
        assertEquals("the listener stops with its last observer", 0, remote.subscriptionCount.value)
        appScope.cancel()
    }

    @Test
    fun `a backend that keeps no packs, and nobody signed in, start no listener`() = runTest {
        val appScope = appScope()
        val sync = sync(appScope)

        every { packSource.isSupported } returns false
        val first = launch(UnconfinedTestDispatcher(testScheduler)) { sync.whileObserved.collect {} }
        first.cancel()
        every { packSource.isSupported } returns true
        every { authSource.currentUserId } returns null
        val second = launch(UnconfinedTestDispatcher(testScheduler)) { sync.whileObserved.collect {} }
        second.cancel()

        verify(exactly = 0) { packSource.observeOwnPacks(any()) }
        appScope.cancel()
    }

    @Test
    fun `a listener that fails ends quietly, and the next observer starts a new one`() = runTest {
        val appScope = appScope()
        every { packSource.observeOwnPacks("uid1") } returns flow { throw IOException("PERMISSION_DENIED") }
        val sync = sync(appScope)

        val first = launch(UnconfinedTestDispatcher(testScheduler)) { sync.whileObserved.collect {} }
        first.cancel()
        val second = launch(UnconfinedTestDispatcher(testScheduler)) { sync.whileObserved.collect {} }
        second.cancel()

        verify(exactly = 2) { packSource.observeOwnPacks("uid1") }
        appScope.cancel()
    }

    @Test
    fun `a sign-out stops the sync, runs, and then forgets the recents`() = runTest {
        val appScope = appScope()
        val steps = mutableListOf<String>()
        every { scheduler.cancel() } answers { steps += "cancel" }
        coEvery { preferences.clearStickerLists() } answers { steps += "recents" }

        sync(appScope).signingOut { steps += "sign-out" }

        assertEquals(listOf("cancel", "sign-out", "recents"), steps)
        appScope.cancel()
    }

    @Test
    fun `a sign-out finishes when its caller is cancelled half-way`() = runTest {
        val appScope = appScope()
        val steps = mutableListOf<String>()
        // Like a DataStore edit, which does not run for a cancelled caller.
        coEvery { preferences.clearStickerLists() } coAnswers {
            yield()
            steps += "recents"
        }
        // The settings screen leaves in the click that signs out, which cancels its ViewModel's scope.
        val caller = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())

        caller.launch {
            sync(appScope).signingOut {
                steps += "sign-out"
                caller.cancel()
            }
        }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("sign-out", "recents"), steps)
        appScope.cancel()
    }

    @Test
    fun `a sign-out that waits for a merge is not lost when its caller is cancelled`() = runTest {
        val appScope = appScope()
        val sync = sync(appScope)
        val merging = CompletableDeferred<Unit>()
        coEvery { stickerDao.applyRemotePack(any(), any(), any()) } coAnswers {
            merging.await()
            true
        }
        val merge = launch(UnconfinedTestDispatcher(testScheduler)) {
            sync.apply("uid1", StickerPackChanges(listOf(manifest("p1")), emptyList()))
        }
        val caller = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        var signedOut = false
        caller.launch { sync.signingOut { signedOut = true } }

        caller.cancel()
        merging.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertTrue("the tables are cleared and the user is signed out", signedOut)
        merge.cancel()
        appScope.cancel()
    }
}
