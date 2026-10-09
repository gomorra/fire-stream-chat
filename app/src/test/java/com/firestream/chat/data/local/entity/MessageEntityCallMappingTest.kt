package com.firestream.chat.data.local.entity

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Room-level mapping of a `CALL` message's kind. */
class MessageEntityCallMappingTest {

    private fun callMessage(video: Boolean) = Message(
        id = "msg1",
        chatId = "chat1",
        senderId = "user1",
        type = MessageType.CALL,
        content = "hangup",
        duration = 42,
        isVideoCall = video,
    )

    @Test
    fun `a video call round-trips as a video call`() {
        val entity = MessageEntity.fromDomain(callMessage(video = true))

        assertTrue(entity.isVideoCall)
        assertTrue(entity.toDomain().isVideoCall)
        assertEquals(42, entity.toDomain().duration)
    }

    @Test
    fun `a voice call round-trips as a voice call`() {
        val entity = MessageEntity.fromDomain(callMessage(video = false))

        assertFalse(entity.isVideoCall)
        assertFalse(entity.toDomain().isVideoCall)
    }

    // The kind is a backend column: it must be on the record a snapshot upserts, or a sync
    // would never write it.
    @Test
    fun `the kind is carried by the record a snapshot upserts`() {
        assertTrue(MessageRecord.fromDomain(callMessage(video = true)).isVideoCall)
    }

    @Test
    fun `a record written without the kind is a voice call`() {
        val record = MessageRecord(
            id = "msg1",
            chatId = "chat1",
            senderId = "user1",
            content = "hangup",
            type = MessageType.CALL.name,
            mediaUrl = null,
            mediaThumbnailUrl = null,
            status = "SENT",
            replyToId = null,
            timestamp = 0L,
            editedAt = null,
        )

        assertFalse(record.toDomain().isVideoCall)
    }
}
