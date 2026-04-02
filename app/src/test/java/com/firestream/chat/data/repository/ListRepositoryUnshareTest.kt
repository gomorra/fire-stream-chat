package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.ListDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.ListEntity
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirestoreListHistorySource
import com.firestream.chat.data.remote.firebase.FirestoreListSource
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.UserRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListRepositoryUnshareTest {

    private val testDispatcher = StandardTestDispatcher()

    private val listDao = mockk<ListDao>(relaxed = true)
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val listSource = mockk<FirestoreListSource> {
        // observeMyLists is called by ensureListSyncRunning() inside observeList();
        // return an empty flow so the retry loop never interferes with test assertions.
        every { observeMyLists(any()) } returns flowOf(emptyList())
    }
    private val historySource = mockk<FirestoreListHistorySource>(relaxed = true)
    private val authSource = mockk<FirebaseAuthSource>()
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>()
    private val messageRepository = mockk<dagger.Lazy<MessageRepository>>()
    private val userRepository = mockk<dagger.Lazy<UserRepository>>()

    private lateinit var repository: ListRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { authSource.currentUserId } returns "receiver1"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createRepository(): ListRepositoryImpl = ListRepositoryImpl(
        listDao, messageDao, listSource, historySource, authSource,
        chatRepository, messageRepository, userRepository
    )

    @Test
    fun `observeList deletes from Room when user is no longer a participant`() = runTest {
        // Firestore returns list with receiver removed from participants
        val firestoreList = ListData(
            id = "list1",
            title = "Shopping",
            type = ListType.SHOPPING,
            createdBy = "owner1",
            participants = listOf("owner1"), // receiver1 removed
            sharedChatIds = emptyList()
        )
        every { listSource.observeList("list1") } returns flowOf(firestoreList)

        val staleEntity = ListEntity.fromDomain(
            firestoreList.copy(
                participants = listOf("owner1", "receiver1"),
                sharedChatIds = listOf("chat1")
            )
        )
        every { listDao.observeById("list1") } returns flowOf(staleEntity)
        coEvery { listDao.delete("list1") } just Runs

        repository = createRepository()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeList("list1").first()
        }
        advanceUntilIdle()
        job.cancel()

        // Should delete, NOT insert — receiver is no longer a participant
        coVerify { listDao.delete("list1") }
        coVerify(exactly = 0) { listDao.insert(any()) }
    }

    @Test
    fun `observeList inserts when user is a participant`() = runTest {
        val firestoreList = ListData(
            id = "list2",
            title = "Shared To Two Chats",
            type = ListType.CHECKLIST,
            createdBy = "owner1",
            participants = listOf("owner1", "receiver1"),
            sharedChatIds = listOf("chatA", "chatB")
        )
        every { listSource.observeList("list2") } returns flowOf(firestoreList)

        val entity = ListEntity.fromDomain(firestoreList)
        every { listDao.observeById("list2") } returns flowOf(entity)
        coEvery { listDao.insert(any()) } just Runs

        repository = createRepository()

        val insertSlot = slot<ListEntity>()
        coEvery { listDao.insert(capture(insertSlot)) } just Runs

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeList("list2").first()
        }
        advanceUntilIdle()
        job.cancel()

        val parsed = org.json.JSONArray(insertSlot.captured.sharedChatIds)
        val ids = List(parsed.length()) { i -> parsed.getString(i) }
        assertEquals(listOf("chatA", "chatB"), ids)
    }
}
