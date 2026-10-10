package com.firestream.chat.data.repository

import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.remote.source.KlipyMediaSource
import com.firestream.chat.domain.model.OnlineMedia
import com.firestream.chat.domain.model.OnlineMediaKind
import com.firestream.chat.domain.model.OnlineMediaPage
import com.firestream.chat.domain.model.OnlineMediaRendition
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class OnlineMediaRepositoryImplTest {

    private val source = mockk<KlipyMediaSource>()
    private val preferences = mockk<PreferencesDataStore>()
    private val repository = OnlineMediaRepositoryImpl(source, preferences)
    private val page = OnlineMediaPage(emptyList(), page = 2, hasNext = true)

    @Before
    fun setUp() {
        every { source.isAvailable } returns true
        coEvery { preferences.klipyCustomerId() } returns CUSTOMER
    }

    @Test
    fun `every call carries the device's customer id`() = runTest {
        val rendition = OnlineMediaRendition("https://static.klipy.com/a.webp", 1, 1, "image/webp")
        val media = OnlineMedia(OnlineMediaKind.STICKER, "cat-wave", "Cat", rendition, rendition)
        coEvery { source.search(OnlineMediaKind.GIF, "cat", 2, CUSTOMER) } returns page
        coEvery { source.trending(OnlineMediaKind.STICKER, 1, CUSTOMER) } returns page
        coEvery { source.reportShare(OnlineMediaKind.STICKER, "cat-wave", CUSTOMER) } returns Unit

        assertEquals(page, repository.search(OnlineMediaKind.GIF, "cat", 2).getOrThrow())
        assertEquals(page, repository.trending(OnlineMediaKind.STICKER).getOrThrow())
        assertTrue(repository.reportShare(media).isSuccess)

        coVerify(exactly = 1) { source.reportShare(OnlineMediaKind.STICKER, "cat-wave", CUSTOMER) }
    }

    @Test
    fun `a build without a key fails every call and makes no customer id`() = runTest {
        every { source.isAvailable } returns false

        assertFalse(repository.isAvailable)
        assertTrue(repository.search(OnlineMediaKind.GIF, "cat").isFailure)
        assertTrue(repository.trending(OnlineMediaKind.GIF).isFailure)

        coVerify(exactly = 0) { preferences.klipyCustomerId() }
        coVerify(exactly = 0) { source.search(any(), any(), any(), any()) }
    }

    @Test
    fun `a failed request is a failure, and a cancelled one is cancelled`() = runTest {
        coEvery { source.trending(any(), any(), any()) } throws IOException("Could not reach KLIPY")
        coEvery { source.search(any(), any(), any(), any()) } throws CancellationException("replaced")

        assertTrue(repository.trending(OnlineMediaKind.GIF).exceptionOrNull() is IOException)
        val cancelled = runCatching { repository.search(OnlineMediaKind.GIF, "cat") }
        assertTrue(cancelled.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `the first-use notice is the stored flag, and accepting it writes the flag`() = runTest {
        every { preferences.klipyNoticeAcceptedFlow } returns flowOf(false)
        coEvery { preferences.setKlipyNoticeAccepted() } returns Unit

        assertFalse(repository.noticeAccepted.first())
        repository.acceptNotice()

        coVerify(exactly = 1) { preferences.setKlipyNoticeAccepted() }
        coVerify(exactly = 0) { preferences.klipyCustomerId() }
    }

    private companion object {
        const val CUSTOMER = "3f0c9c7e-1111-4222-8333-444455556666"
    }
}
