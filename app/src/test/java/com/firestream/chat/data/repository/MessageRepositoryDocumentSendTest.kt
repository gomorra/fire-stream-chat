package com.firestream.chat.data.repository

import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.util.DocumentFiles
import com.firestream.chat.data.util.DocumentInfo
import com.firestream.chat.domain.model.MediaLimitException
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.util.MAX_DOCUMENT_BYTES
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * A document's identity travels with it (`docs/plans/file-handling.md` step 1).
 *
 * Regression: a picked file used to be sent with only its (empty) caption as
 * `content`, so the bubble showed a bare paperclip and the receiver had no name,
 * size or type to open it by.
 */
class MessageRepositoryDocumentSendTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val authSource = mockk<AuthSource>()
    private val documentFiles = mockk<DocumentFiles>()
    private val inserted = mutableListOf<MessageEntity>()

    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "uid1"
        coEvery { messageDao.insertOutbox(any()) } answers { inserted += firstArg<MessageEntity>() }
        coEvery { documentFiles.describe("content://pick/report") } returns DocumentInfo("Report 2026.pdf", 48_213L)
        val preferences = mockk<PreferencesDataStore>(relaxed = true) {
            every { sendImagesFullQualityFlow } returns flowOf(false)
        }
        repository = messageRepository(
            messageDao = messageDao,
            authSource = authSource,
            preferencesDataStore = preferences,
            documentFiles = documentFiles,
        )
    }

    @Test
    fun `a picked document is queued with its name, size and type, and the caption stays the caption`() = runTest {
        repository.sendMediaMessage("chat1", "content://pick/report", "application/pdf", "", caption = "for Monday", isHd = null)

        val row = inserted.single().toDomain()
        assertEquals(MessageType.DOCUMENT, row.type)
        assertEquals("Report 2026.pdf", row.fileName)
        assertEquals(48_213L, row.fileSize)
        assertEquals("application/pdf", row.mimeType)
        assertEquals("for Monday", row.content)
    }

    @Test
    fun `a photo carries no file fields and never asks for a description`() = runTest {
        repository.sendMediaMessage("chat1", "content://pick/photo", "image/jpeg", "", caption = "", isHd = null)

        val row = inserted.single().toDomain()
        assertNull(row.fileName)
        assertNull(row.fileSize)
        assertNull(row.mimeType)
        coVerify(exactly = 0) { documentFiles.describe(any()) }
    }

    @Test
    fun `a document over the size limit is refused before any row is written`() = runTest {
        coEvery { documentFiles.describe("content://pick/huge") } returns DocumentInfo("backup.zip", MAX_DOCUMENT_BYTES + 1)

        val result = repository.sendMediaMessage("chat1", "content://pick/huge", "application/zip", "", caption = "", isHd = null)

        assertTrue(result.exceptionOrNull() is MediaLimitException)
        assertTrue(inserted.isEmpty())
    }

    // A provider that reports no size gets past the pre-insert check; the staged
    // copy's real length catches it. Sparse, so the test writes nothing real.
    @Test
    fun `a document of unreported size is failed once its copy proves too large`() = runTest {
        val huge = File.createTempFile("huge", ".bin").apply {
            RandomAccessFile(this, "rw").use { it.setLength(MAX_DOCUMENT_BYTES + 1) }
        }
        try {
            coEvery { documentFiles.describe(huge.absolutePath) } returns DocumentInfo("backup.bin", null)

            val result = repository.sendMediaMessage("chat1", huge.absolutePath, "application/octet-stream", "", caption = "", isHd = null)

            assertTrue(result.exceptionOrNull() is MediaLimitException)
            val id = inserted.single().id
            coVerify { messageDao.updateMessageStatus(id, MessageStatus.FAILED.name) }
        } finally {
            huge.delete()
        }
    }
}
