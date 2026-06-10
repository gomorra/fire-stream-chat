package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.ListDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.ListEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.ListHistorySource
import com.firestream.chat.data.remote.source.ListSource
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.UserRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * White-box **tripwire** for the per-list mutex that guards the three sync-path writes
 * into Room (`TECH_DEBT.md` → "Sync-path regression coverage … per-list-mutex tripwire").
 *
 * The bug it guards against is `eed7519`: a refactor that strips one of the three
 * `mutexFor(listId).withLock { … listDao.insert(…) }` blocks in
 *   1. `observeList`'s metadata listener,
 *   2. `observeList`'s items listener,
 *   3. `ensureListSyncRunning`'s `observeMyLists` merge,
 * lets a fresh item write get stomped by a stale-cached write — the receiver never sees
 * the new item. TECH_DEBT explicitly warns that a *behavioral* race repro can't catch this
 * with synchronous DAO mocks (the interleaving collapses → false negative), and the pulled
 * test proved it. So this is a structural assertion instead: it instruments `listDao.insert`
 * to record whether the per-list `Mutex` is held at the moment of the call, drives each of
 * the three paths in isolation, and asserts the lock was held.
 *
 * It deliberately does **not** assert that *every* insert is locked — `createList`,
 * `getSharedListsForChat` (`insertAll`), and `fetchAndCacheList` insert without the lock by
 * design, so each guarded path is exercised on its own with the other two suppressed.
 *
 * If any of the three `withLock` wrappers is removed, the corresponding test fails because
 * the mutex is observed unlocked at insert time (single-threaded test dispatcher → the lock
 * state reflects only this path's writer).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListRepositoryMutexTripwireTest {

    private val testDispatcher = StandardTestDispatcher()

    private data class InsertObservation(val listId: String, val lockedAtInsert: Boolean)

    private val insertObservations = CopyOnWriteArrayList<InsertObservation>()

    // In-memory ListDao. The insert mock is the tripwire probe: on every call it reflects
    // into the repository's private `listMutexes` map and records whether the mutex for the
    // inserted entity's id is currently locked.
    private val daoStorage = ConcurrentHashMap<String, ListEntity>()
    private val daoFlows = ConcurrentHashMap<String, MutableStateFlow<ListEntity?>>()
    private fun flowFor(id: String): MutableStateFlow<ListEntity?> =
        daoFlows.getOrPut(id) { MutableStateFlow(daoStorage[id]) }

    private val listDao = mockk<ListDao>(relaxed = true).also { dao ->
        coEvery { dao.getById(any()) } answers { daoStorage[firstArg()] }
        coEvery { dao.insert(any()) } answers {
            val entity = firstArg<ListEntity>()
            insertObservations += InsertObservation(entity.id, mutexFor(entity.id)?.isLocked == true)
            daoStorage[entity.id] = entity
            flowFor(entity.id).value = entity
        }
        every { dao.observeById(any()) } answers { flowFor(firstArg()) }
    }

    // Per-test source flows so each guarded path can be driven in isolation.
    private val metadataFlow = MutableStateFlow<ListData?>(null)
    private val itemsFlow = MutableStateFlow<List<ListItem>>(emptyList())
    private val myListsFlow = MutableStateFlow<List<ListData>>(emptyList())
    // observeList() also fires ensureListSyncRunning + the metadata/items listeners; tests
    // that want one path silenced point the others at emptyFlow so they never insert.
    private var emitMetadata = false
    private var emitItems = false

    private val listSource = mockk<ListSource>(relaxed = true).also { src ->
        every { src.observeList(LIST_ID) } answers { if (emitMetadata) metadataFlow else emptyFlow() }
        every { src.observeItems(LIST_ID) } answers { if (emitItems) itemsFlow.map { it.toList() } else emptyFlow() }
        every { src.observeMyLists(any()) } returns myListsFlow
        coEvery { src.migrateEmbeddedItemsIfNeeded(any()) } answers { /* already migrated */ }
    }

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val historySource = mockk<ListHistorySource>(relaxed = true)
    private val authSource = mockk<AuthSource>().also { every { it.currentUserId } returns USER_ID }
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>(relaxed = true)
    private val messageRepository = mockk<dagger.Lazy<MessageRepository>>(relaxed = true)
    private val userRepository = mockk<dagger.Lazy<UserRepository>>(relaxed = true).also {
        every { it.get() } returns mockk(relaxed = true) {
            coEvery { getUserById(any()) } returns Result.success(User(uid = USER_ID, displayName = "Tester"))
        }
    }

    private lateinit var repository: ListRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = ListRepositoryImpl(
            listDao, messageDao, listSource, historySource, authSource,
            chatRepository, messageRepository, userRepository
        )
        // Pin the repository's internal SupervisorJob scope (used by ensureListSyncRunning)
        // to the test dispatcher so advanceUntilIdle drives it deterministically instead of
        // Dispatchers.Default on a real thread.
        setPrivateField(repository, "historyScope", CoroutineScope(testDispatcher))
    }

    @After
    fun tearDown() {
        (getPrivateField(repository, "historyScope") as CoroutineScope).cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `observeList metadata listener inserts under the per-list mutex`() = runTest(testDispatcher) {
        // Only the metadata listener emits → its insert is the only one for LIST_ID.
        emitMetadata = true
        metadataFlow.value = listData(items = emptyList())

        val job = launch { repository.observeList(LIST_ID).collect { } }
        advanceUntilIdle()

        assertAllInsertsForListLocked("metadata listener")
        job.cancel()
    }

    @Test
    fun `observeList items listener inserts under the per-list mutex`() = runTest(testDispatcher) {
        // Seed an entity directly (not through the probed insert) so the items listener's
        // getById != null guard passes; emit items that differ so it actually writes.
        seedEntity(items = emptyList())
        emitItems = true
        itemsFlow.value = listOf(ListItem(id = "i1", text = "milk", order = 0))

        val job = launch { repository.observeList(LIST_ID).collect { } }
        advanceUntilIdle()

        assertAllInsertsForListLocked("items listener")
        job.cancel()
    }

    @Test
    fun `ensureListSyncRunning observeMyLists merge inserts under the per-list mutex`() = runTest(testDispatcher) {
        // Only the observeMyLists sync loop emits a list → its merge-insert is the only one.
        myListsFlow.value = listOf(listData(items = emptyList()))

        val job = launch { repository.observeList(LIST_ID).collect { } }
        advanceUntilIdle()

        assertAllInsertsForListLocked("ensureListSyncRunning")
        job.cancel()
    }

    // --- helpers -------------------------------------------------------------------------

    private fun assertAllInsertsForListLocked(path: String) {
        val forList = insertObservations.filter { it.listId == LIST_ID }
        assertTrue(
            "$path should have written to Room at least once (else the test passes vacuously)",
            forList.isNotEmpty()
        )
        assertTrue(
            "$path must hold mutexFor($LIST_ID) at every listDao.insert — a stripped " +
                "withLock would re-introduce eed7519. Observations: $forList",
            forList.all { it.lockedAtInsert }
        )
    }

    private fun seedEntity(items: List<ListItem>) {
        daoStorage[LIST_ID] = ListEntity.fromDomain(listData(items = items))
        flowFor(LIST_ID).value = daoStorage[LIST_ID]
    }

    private fun listData(items: List<ListItem>) = ListData(
        id = LIST_ID,
        title = "Groceries",
        type = ListType.SHOPPING,
        createdBy = USER_ID,
        participants = listOf(USER_ID),
        items = items,
    )

    @Suppress("UNCHECKED_CAST")
    private fun mutexFor(listId: String): Mutex? {
        val map = getPrivateField(repository, "listMutexes") as ConcurrentHashMap<String, Mutex>
        return map[listId]
    }

    private fun getPrivateField(target: Any, name: String): Any =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)!!

    private fun setPrivateField(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    companion object {
        private const val LIST_ID = "list1"
        private const val USER_ID = "user1"
    }
}
