package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.ListDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.ListEntity
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirestoreListHistorySource
import com.firestream.chat.data.remote.firebase.FirestoreListSource
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Regression tests for the list sync bugs fixed by the subcollection refactor:
 * rapid adds lagged, rapid toggles jumped back to unchecked, and cross-device
 * edits clobbered each other. These tests use an in-memory stand-in for Firestore
 * so we can interleave operations the way the real client would and assert every
 * intent survives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListRepositoryImplRaceTest {

    private val testDispatcher = StandardTestDispatcher()

    // In-memory state shared by the fakes (simulates the Firestore doc + items subcollection).
    private val metadata = MutableStateFlow(
        ListData(
            id = LIST_ID,
            title = "Groceries",
            type = ListType.SHOPPING,
            createdBy = USER_ID,
            participants = listOf(USER_ID),
        )
    )
    private val items = MutableStateFlow<List<ListItem>>(emptyList())
    // Per-doc atomicity lock: each batch write to the fake must be all-or-nothing,
    // mirroring Firestore's single-document write guarantee.
    private val fakeFirestoreLock = Mutex()

    private val listSource = mockk<FirestoreListSource>(relaxed = true).also { src ->
        every { src.observeList(LIST_ID) } returns metadata.map { it as ListData? }
        every { src.observeItems(LIST_ID) } returns items.map { it.toList() }
        every { src.observeMyLists(any()) } returns flowOf(emptyList())
        coEvery { src.migrateEmbeddedItemsIfNeeded(any()) } answers { /* no-op: already migrated */ }
        coEvery { src.addItem(LIST_ID, any()) } coAnswers {
            val item = secondArg<ListItem>()
            // Delay simulates network round-trip so concurrent callers interleave at
            // this suspension point; without the repository's per-list mutex a second
            // caller would read stale Room state here.
            delay(50)
            fakeFirestoreLock.withLock {
                items.value = items.value + item
                metadata.value = metadata.value.copy(
                    itemCount = metadata.value.itemCount + 1,
                    checkedCount = metadata.value.checkedCount + if (item.isChecked) 1 else 0,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        coEvery { src.setItemChecked(LIST_ID, any(), any()) } coAnswers {
            val id = secondArg<String>()
            val checked = thirdArg<Boolean>()
            delay(50)
            fakeFirestoreLock.withLock {
                val prev = items.value.find { it.id == id }?.isChecked
                items.value = items.value.map {
                    if (it.id == id) it.copy(isChecked = checked) else it
                }
                if (prev != null && prev != checked) {
                    metadata.value = metadata.value.copy(
                        checkedCount = metadata.value.checkedCount + if (checked) 1 else -1
                    )
                }
            }
        }
        coEvery { src.updateItem(LIST_ID, any(), any(), any(), any()) } coAnswers {
            val id = secondArg<String>()
            val text = thirdArg<String>()
            val quantity = arg<String?>(3)
            val unit = arg<String?>(4)
            delay(50)
            fakeFirestoreLock.withLock {
                items.value = items.value.map {
                    if (it.id == id) it.copy(text = text, quantity = quantity, unit = unit) else it
                }
            }
        }
        coEvery { src.deleteItem(LIST_ID, any(), any()) } coAnswers {
            val id = secondArg<String>()
            val wasChecked = thirdArg<Boolean>()
            delay(50)
            fakeFirestoreLock.withLock {
                items.value = items.value.filter { it.id != id }
                metadata.value = metadata.value.copy(
                    itemCount = (metadata.value.itemCount - 1).coerceAtLeast(0),
                    checkedCount = (metadata.value.checkedCount - if (wasChecked) 1 else 0).coerceAtLeast(0)
                )
            }
        }
        coEvery { src.clearCheckedItems(LIST_ID, any()) } coAnswers {
            val ids = secondArg<List<String>>()
            delay(50)
            fakeFirestoreLock.withLock {
                items.value = items.value.filter { it.id !in ids }
                metadata.value = metadata.value.copy(
                    itemCount = (metadata.value.itemCount - ids.size).coerceAtLeast(0),
                    checkedCount = 0
                )
            }
        }
    }

    // In-memory ListDao — lets two concurrent mutations compete for real (which is the
    // whole point of these tests). `observeById` pushes on every insert so the
    // repository's outer flow emits without needing Firestore round-trips in the test.
    private val daoStorage = ConcurrentHashMap<String, ListEntity>()
    private val daoFlows = ConcurrentHashMap<String, MutableStateFlow<ListEntity?>>()
    private fun flowFor(id: String): MutableStateFlow<ListEntity?> =
        daoFlows.getOrPut(id) { MutableStateFlow(daoStorage[id]) }

    private val listDao = mockk<ListDao>(relaxed = true).also { dao ->
        coEvery { dao.getById(any()) } answers { daoStorage[firstArg()] }
        coEvery { dao.insert(any()) } answers {
            val entity = firstArg<ListEntity>()
            daoStorage[entity.id] = entity
            flowFor(entity.id).value = entity
        }
        every { dao.observeById(any()) } answers { flowFor(firstArg()) }
    }

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val historySource = mockk<FirestoreListHistorySource>(relaxed = true)
    private val authSource = mockk<FirebaseAuthSource>().also {
        every { it.currentUserId } returns USER_ID
    }
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>(relaxed = true)
    private val messageRepository = mockk<dagger.Lazy<MessageRepository>>(relaxed = true)
    private val userRepository = mockk<dagger.Lazy<UserRepository>>(relaxed = true).also {
        every { it.get() } returns mockk(relaxed = true) {
            coEvery { getUserById(any()) } returns Result.success(
                User(uid = USER_ID, displayName = "Tester")
            )
        }
    }

    private lateinit var repository: ListRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Seed Room with a participant list so the repository's observeList participant
        // check sees us as a participant.
        daoStorage[LIST_ID] = ListEntity.fromDomain(metadata.value)
        flowFor(LIST_ID).value = daoStorage[LIST_ID]
        repository = ListRepositoryImpl(
            listDao, messageDao, listSource, historySource, authSource,
            chatRepository, messageRepository, userRepository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `concurrent addItem calls all persist`() = runTest(testDispatcher) {
        val texts = (1..10).map { "item-$it" }
        val results = texts.map { text ->
            async {
                repository.addItem(LIST_ID, UUID.randomUUID().toString(), text, null, null)
            }
        }.awaitAll()

        advanceUntilIdle()

        assertTrue("all addItem calls should succeed: $results", results.all { it.isSuccess })
        assertEquals(
            "every added item lands in Firestore subcollection",
            texts.toSet(),
            items.value.map { it.text }.toSet()
        )
        assertEquals(10, metadata.value.itemCount)
    }

    @Test
    fun `concurrent toggleItemChecked calls all persist - no jump-back`() = runTest(testDispatcher) {
        // Seed 5 unchecked items.
        val seed = (1..5).map { ListItem(id = "i$it", text = "t$it", order = it) }
        items.value = seed
        metadata.value = metadata.value.copy(itemCount = seed.size)
        daoStorage[LIST_ID] = ListEntity.fromDomain(metadata.value.copy(items = seed))
        flowFor(LIST_ID).value = daoStorage[LIST_ID]

        val results = seed.map { item ->
            async { repository.toggleItemChecked(LIST_ID, item.id, checked = true) }
        }.awaitAll()

        advanceUntilIdle()

        assertTrue("all toggles should succeed: $results", results.all { it.isSuccess })
        assertTrue(
            "every toggle persists in Firestore — no jump-back",
            items.value.all { it.isChecked }
        )
        assertEquals(5, metadata.value.checkedCount)
    }

    @Test
    fun `concurrent add + toggle on different items both survive`() = runTest(testDispatcher) {
        val seed = listOf(ListItem(id = "existing", text = "old", order = 0))
        items.value = seed
        metadata.value = metadata.value.copy(itemCount = 1)
        daoStorage[LIST_ID] = ListEntity.fromDomain(metadata.value.copy(items = seed))
        flowFor(LIST_ID).value = daoStorage[LIST_ID]

        val newId = UUID.randomUUID().toString()
        val addJob = async { repository.addItem(LIST_ID, newId, "new", null, null) }
        val toggleJob = async { repository.toggleItemChecked(LIST_ID, "existing", checked = true) }
        addJob.await()
        toggleJob.await()
        advanceUntilIdle()

        assertEquals(
            "both the added item and the toggled item persist",
            setOf("existing" to true, newId to false),
            items.value.map { it.id to it.isChecked }.toSet()
        )
        assertEquals(2, metadata.value.itemCount)
        assertEquals(1, metadata.value.checkedCount)
    }

    @Test
    fun `rapid toggle back-and-forth ends on the caller's final intent`() = runTest(testDispatcher) {
        val seed = listOf(ListItem(id = "i1", text = "milk", order = 0))
        items.value = seed
        metadata.value = metadata.value.copy(itemCount = 1)
        daoStorage[LIST_ID] = ListEntity.fromDomain(metadata.value.copy(items = seed))
        flowFor(LIST_ID).value = daoStorage[LIST_ID]

        // Classic bug scenario: user taps check, then unchecks, then checks again, fast.
        // With the old read-modify-write full-array approach the final state was
        // non-deterministic; with the subcollection + per-list mutex each intent is
        // applied in call order.
        val seq = listOf(true, false, true, false, true)
        seq.forEach { target ->
            repository.toggleItemChecked(LIST_ID, "i1", target)
        }
        advanceUntilIdle()

        assertEquals(true, items.value.single { it.id == "i1" }.isChecked)
        assertEquals(1, metadata.value.checkedCount)
    }

    @Test
    fun `concurrent clearChecked + add are both applied`() = runTest(testDispatcher) {
        val seed = listOf(
            ListItem(id = "a", text = "a", isChecked = true, order = 0),
            ListItem(id = "b", text = "b", isChecked = true, order = 1),
            ListItem(id = "c", text = "c", isChecked = false, order = 2),
        )
        items.value = seed
        metadata.value = metadata.value.copy(itemCount = 3, checkedCount = 2)
        daoStorage[LIST_ID] = ListEntity.fromDomain(metadata.value.copy(items = seed))
        flowFor(LIST_ID).value = daoStorage[LIST_ID]

        val newId = UUID.randomUUID().toString()
        val clear = async { repository.clearCheckedItems(LIST_ID) }
        val add = async { repository.addItem(LIST_ID, newId, "new", null, null) }
        clear.await()
        add.await()
        advanceUntilIdle()

        val finalIds = items.value.map { it.id }.toSet()
        assertTrue("c survives (not checked)", "c" in finalIds)
        assertTrue("new item survives the concurrent clear", newId in finalIds)
        assertTrue("cleared checked items are gone", "a" !in finalIds && "b" !in finalIds)
    }

    @Test
    fun `toggleItemChecked waits for in-flight migration before hitting Firestore`() = runTest(testDispatcher) {
        // Simulate the pre-refactor case where migration is still running when the user
        // taps. Without the gate, setItemChecked would fire a batch.update on a
        // subcollection doc that hasn't been created yet → Firestore NOT_FOUND.
        val migrationStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val migrationCanComplete = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { listSource.migrateEmbeddedItemsIfNeeded(LIST_ID) } coAnswers {
            migrationStarted.complete(Unit)
            migrationCanComplete.await()
        }

        val seed = listOf(ListItem(id = "i1", text = "milk"))
        items.value = seed
        metadata.value = metadata.value.copy(itemCount = 1)
        daoStorage[LIST_ID] = ListEntity.fromDomain(metadata.value.copy(items = seed))
        flowFor(LIST_ID).value = daoStorage[LIST_ID]

        // Open observeList to kick off migration (as the detail screen would).
        val observeJob = launch { repository.observeList(LIST_ID).collect { } }
        advanceUntilIdle()
        // Migration is blocked at the suspension point — mutation should not proceed.
        assertTrue("migration should have started", migrationStarted.isCompleted)

        val toggle = async { repository.toggleItemChecked(LIST_ID, "i1", true) }
        advanceUntilIdle()
        assertTrue(
            "toggle must not reach Firestore while migration is in flight",
            items.value.single { it.id == "i1" }.isChecked.not()
        )

        // Release migration, then the toggle can drain through.
        migrationCanComplete.complete(Unit)
        toggle.await()
        advanceUntilIdle()

        assertEquals(true, items.value.single { it.id == "i1" }.isChecked)
        observeJob.cancel()
    }

    @Test
    fun `observeList triggers embedded-items migration exactly once per list`() = runTest(testDispatcher) {
        // Subscribe twice in a row — the repository's ensureMigrated Deferred cache
        // should fire the migration only on the first subscription, so one-shot work
        // (batch writes to Firestore) isn't replayed on every screen re-open.
        val job1 = launch { repository.observeList(LIST_ID).collect { /* drain */ } }
        advanceUntilIdle()
        job1.cancel()

        val job2 = launch { repository.observeList(LIST_ID).collect { /* drain */ } }
        advanceUntilIdle()
        job2.cancel()

        io.mockk.coVerify(exactly = 1) { listSource.migrateEmbeddedItemsIfNeeded(LIST_ID) }
    }

    companion object {
        private const val LIST_ID = "list1"
        private const val USER_ID = "user1"
    }
}
