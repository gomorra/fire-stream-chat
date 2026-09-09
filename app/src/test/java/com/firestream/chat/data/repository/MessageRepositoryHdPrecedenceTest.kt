package com.firestream.chat.data.repository

import android.net.ConnectivityManager
import android.net.Uri
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.VideoQualityOption
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.StorageSource
import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.data.util.ImageCompressor
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.data.util.VideoTranscoder
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Per-image HD beats the global preference; a null follows it
 * (`.claude/plans/image-editor.md` §2.5).
 *
 * Both halves matter. The override is the feature; the fall-through is the
 * promise that nothing changes for anyone who never touches the toggle — every
 * caller that does not offer the choice (the share sheet, a retry) omits the
 * argument entirely and must keep behaving as it did.
 *
 * Asserted at two points, because a mismatch between them would ship an image
 * whose badge lies about its own bytes: the quality flag handed to
 * [ImageCompressor], and the `isHd` on the optimistic Room row that renders the
 * HD badge. The compressor then throws, which is enough — the placeholder is
 * inserted before any IO, so neither assertion needs a working upload.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryHdPrecedenceTest {

    private val testDispatcher = StandardTestDispatcher()

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val chatDao = mockk<ChatDao>(relaxed = true)
    private val messageSource = mockk<MessageSource>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val signalManager = mockk<SignalManager>(relaxed = true)
    private val storageSource = mockk<StorageSource>(relaxed = true)
    private val chatRepository = mockk<dagger.Lazy<ChatRepository>>()
    private val mediaFileManager = mockk<MediaFileManager>(relaxed = true)
    private val imageCompressor = mockk<ImageCompressor>()
    private val videoTranscoder = mockk<VideoTranscoder>(relaxed = true)
    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true)
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
    private val listRepository = mockk<dagger.Lazy<ListRepository>>()
    private val userSource = mockk<UserSource>(relaxed = true)

    private val insertedEntities = mutableListOf<MessageEntity>()
    private val compressorFullQuality = mutableListOf<Boolean>()

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Uri.parse is an Android stub; mock it so the JVM unit test can call it.
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { mockk<Uri>(relaxed = true) }
        every { Uri.fromFile(any()) } answers { mockk<Uri>(relaxed = true) }

        every { authSource.currentUserId } returns "uid1"
        every { preferencesDataStore.videoQualityFlow } returns flowOf(VideoQualityOption.STANDARD)

        coEvery { messageDao.insertMessage(any()) } answers {
            insertedEntities += firstArg<MessageEntity>()
        }
        coEvery { messageDao.updateMessageStatus(any(), any()) } just Runs

        // Record the quality flag, then fail: everything asserted here happens
        // before the upload, and stubbing a whole successful send would only add
        // ways for this test to break for unrelated reasons.
        coEvery { imageCompressor.processImage(any(), any()) } answers {
            compressorFullQuality += secondArg<Boolean>()
            throw IllegalArgumentException("stop here")
        }

        repository = MessageRepositoryImpl(
            messageDao, chatDao, messageSource, authSource, signalManager, storageSource, chatRepository,
            listRepository, mediaFileManager, imageCompressor, videoTranscoder, preferencesDataStore,
            connectivityManager, userSource
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Uri::class)
    }

    private suspend fun send(isHd: Boolean?) {
        repository.sendMediaMessage(
            chatId = "chat1",
            uri = "content://pick/1",
            mimeType = "image/jpeg",
            recipientId = "recipient1",
            caption = "",
            isHd = isHd,
        )
    }

    @Test
    fun `per-item true wins over a global preference of false`() = runTest {
        every { preferencesDataStore.sendImagesFullQualityFlow } returns flowOf(false)

        send(isHd = true)

        assertEquals(listOf(true), compressorFullQuality)
        assertEquals(true, insertedEntities.single().isHd)
    }

    @Test
    fun `per-item false wins over a global preference of true`() = runTest {
        every { preferencesDataStore.sendImagesFullQualityFlow } returns flowOf(true)

        send(isHd = false)

        assertEquals(listOf(false), compressorFullQuality)
        assertEquals(false, insertedEntities.single().isHd)
    }

    @Test
    fun `a null override falls through to a global preference of true`() = runTest {
        every { preferencesDataStore.sendImagesFullQualityFlow } returns flowOf(true)

        send(isHd = null)

        assertEquals(listOf(true), compressorFullQuality)
        assertEquals(true, insertedEntities.single().isHd)
    }

    @Test
    fun `a null override falls through to a global preference of false`() = runTest {
        every { preferencesDataStore.sendImagesFullQualityFlow } returns flowOf(false)

        send(isHd = null)

        assertEquals(listOf(false), compressorFullQuality)
        assertEquals(false, insertedEntities.single().isHd)
    }

    @Test
    fun `a caller that omits the argument entirely still follows the preference`() = runTest {
        // The default is what keeps every pre-existing call site — the share
        // sheet among them — compiling and behaving exactly as before.
        every { preferencesDataStore.sendImagesFullQualityFlow } returns flowOf(true)

        repository.sendMediaMessage("chat1", "content://pick/1", "image/jpeg", "recipient1")

        assertEquals(listOf(true), compressorFullQuality)
        assertEquals(true, insertedEntities.single().isHd)
    }
}
