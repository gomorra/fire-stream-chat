package com.firestream.chat.data.outbox

import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a row is queued for, and whether its next attempt uploads — the rules the worker and the scheduler share. */
class OutboxJobTest {

    private fun row(
        status: MessageStatus = MessageStatus.SENDING,
        type: MessageType = MessageType.TEXT,
        mediaUrl: String? = null,
        deletedAt: Long? = null,
    ) = MessageEntity.outbox(
        Message(
            id = "msg1",
            chatId = "chat1",
            senderId = "uid1",
            content = "",
            type = type,
            status = status,
            timestamp = 1_000L,
            mediaUrl = mediaUrl,
            deletedAt = deletedAt,
        ),
        SendTarget.NoPeer,
    )

    @Test
    fun `a SENDING row is a send, a deleted unacknowledged row is a tombstone`() {
        assertEquals(OutboxJob.SEND, row().outboxJob)
        assertEquals(OutboxJob.TOMBSTONE, row(deletedAt = 5_000L).outboxJob)
        assertEquals(OutboxJob.TOMBSTONE, row(status = MessageStatus.FAILED, deletedAt = 5_000L).outboxJob)
    }

    @Test
    fun `a failed, an acknowledged and a directly deleted row owe the backend nothing`() {
        assertNull(row(status = MessageStatus.FAILED).outboxJob)
        assertNull(row(status = MessageStatus.SENT).outboxJob)
        assertNull(row(status = MessageStatus.READ, deletedAt = 5_000L).outboxJob)
    }

    @Test
    fun `only a media send that has not uploaded yet uploads`() {
        assertTrue(row(type = MessageType.IMAGE).needsUpload)
        assertTrue(row(type = MessageType.VOICE).needsUpload)
        assertFalse(row(type = MessageType.TEXT).needsUpload)
        assertFalse("a forward's media is the source's", row(type = MessageType.IMAGE, mediaUrl = "https://storage.example/src").needsUpload)
        assertFalse("a tombstone uploads nothing", row(type = MessageType.IMAGE, deletedAt = 5_000L).needsUpload)
        assertFalse("nothing to do, nothing to upload", row(type = MessageType.IMAGE, status = MessageStatus.SENT).needsUpload)
    }
}
