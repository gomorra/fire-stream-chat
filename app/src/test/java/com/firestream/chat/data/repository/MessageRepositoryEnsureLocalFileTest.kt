package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.outbox.OutboxFiles
import com.firestream.chat.data.util.DocumentFiles
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Open / Share / Save need a readable file the FileProvider can grant (`docs/plans/file-handling.md` step 2). */
class MessageRepositoryEnsureLocalFileTest {

    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val mediaFileManager = mockk<MediaFileManager>()
    private val documentFiles = mockk<DocumentFiles>()
    private val outboxFiles = mockk<OutboxFiles>(relaxed = true)
    private val kept = File.createTempFile("doc1", ".pdf").apply { writeText("%PDF") }

    private val repository = messageRepository(
        messageDao = messageDao,
        mediaFileManager = mediaFileManager,
        documentFiles = documentFiles,
        outboxFiles = outboxFiles,
    )

    private val doc = Message(
        id = "doc1", chatId = "chat1", type = MessageType.DOCUMENT,
        mediaUrl = "https://storage.example/doc1.pdf", fileName = "Report.pdf", mimeType = "application/pdf",
    )

    @After
    fun tearDown() {
        kept.delete()
    }

    @Test
    fun `a kept copy is used as it is, with no download`() = runTest {
        every { documentFiles.owns(kept) } returns true

        val path = repository.ensureLocalFile(doc.copy(localUri = kept.absolutePath)).getOrThrow()

        assertEquals(kept.absolutePath, path)
        coVerify(exactly = 0) { mediaFileManager.downloadFor(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `no local copy downloads one and remembers it on the row`() = runTest {
        coEvery {
            mediaFileManager.downloadFor("chat1", "doc1", MessageType.DOCUMENT, doc.mediaUrl!!, "Report.pdf", "application/pdf")
        } returns kept

        val path = repository.ensureLocalFile(doc).getOrThrow()

        assertEquals(kept.absolutePath, path)
        coVerify(exactly = 1) { messageDao.updateLocalUri("doc1", kept.absolutePath) }
    }

    @Test
    fun `a local file the provider cannot grant is replaced by a download`() = runTest {
        every { documentFiles.owns(kept) } returns false
        every { outboxFiles.isStaged(kept.path) } returns false
        coEvery { mediaFileManager.downloadFor(any(), any(), any(), any(), any(), any()) } returns kept

        repository.ensureLocalFile(doc.copy(localUri = kept.absolutePath)).getOrThrow()

        coVerify(exactly = 1) { mediaFileManager.downloadFor(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a document not uploaded yet and without a readable copy fails`() = runTest {
        val result = repository.ensureLocalFile(doc.copy(mediaUrl = null, localUri = "content://picker/1"))

        assertTrue(result.isFailure)
    }
}
