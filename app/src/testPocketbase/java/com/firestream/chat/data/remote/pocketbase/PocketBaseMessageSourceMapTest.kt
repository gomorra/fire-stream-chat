package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * mapToRaw is the only PB-specific deserialisation that's pure enough to
 * unit-test in isolation. Confirms field-name remap + the v0 nullable
 * defaults so that fields the schema doesn't carry (ciphertext, signalType,
 * mediaWidth/Height, mentions, etc.) come through as null/empty rather than
 * leaking the literal "" or 0 values JSONObject.optString returns.
 */
class PocketBaseMessageSourceMapTest {

    private val subject = PocketBaseMessageSource(
        client = mockk(relaxed = true),
        realtime = mockk(relaxed = true)
    )

    @Test
    fun `mapToRaw remaps PB snake_case fields to RawMessage camelCase`() {
        val record = JSONObject().apply {
            put("id", "m_1")
            put("chat_id", "c_42")
            put("sender_id", "u_99")
            put("content", "hello")
            put("type", MessageType.TEXT.name)
            put("status", MessageStatus.SENT.name)
            put("media_url", "https://cdn/x.jpg")
            put("reply_to_id", "m_0")
            put("timestamp", 1_700_000_000_000L)
        }

        val raw = subject.mapToRaw(record)

        assertEquals("m_1", raw.id)
        assertEquals("c_42", raw.chatId)
        assertEquals("u_99", raw.senderId)
        assertEquals("hello", raw.content)
        assertEquals(MessageType.TEXT.name, raw.type)
        assertEquals(MessageStatus.SENT.name, raw.status)
        assertEquals("https://cdn/x.jpg", raw.mediaUrl)
        assertEquals("m_0", raw.replyToId)
        assertEquals(1_700_000_000_000L, raw.timestamp)
    }

    @Test
    fun `mapToRaw produces null for absent string fields rather than empty string`() {
        // The v0 PB schema doesn't carry media_url or reply_to_id on a plain
        // text message; JSONObject.optString returns "" for them. RawMessage
        // contract requires null, not "" — the repo branches on null.
        val record = JSONObject().apply {
            put("id", "m_2")
            put("chat_id", "c_42")
            put("sender_id", "u_99")
            put("content", "hi")
            put("type", MessageType.TEXT.name)
            put("status", MessageStatus.SENT.name)
            put("timestamp", 1_700_000_000_001L)
        }

        val raw = subject.mapToRaw(record)

        assertNull(raw.mediaUrl)
        assertNull(raw.replyToId)
        // Fields PB v0 doesn't have at all must default to null/empty:
        assertNull(raw.ciphertext)
        assertNull(raw.signalType)
        assertNull(raw.mediaThumbnailUrl)
        assertNull(raw.editedAt)
        assertNull(raw.deletedAt)
        assertNull(raw.duration)
        assertNull(raw.pollData)
        assertNull(raw.listId)
        assertNull(raw.listDiff)
        assertNull(raw.mediaWidth)
        assertNull(raw.mediaHeight)
        assertNull(raw.latitude)
        assertNull(raw.longitude)
        assertEquals(emptyMap<String, String>(), raw.reactions)
        assertEquals(emptyMap<String, Long>(), raw.readBy)
        assertEquals(emptyMap<String, Long>(), raw.deliveredTo)
        assertEquals(emptyList<String>(), raw.mentions)
    }

    @Test
    fun `mapToRaw falls back to TEXT and SENT when type or status missing`() {
        val record = JSONObject().apply {
            put("id", "m_3")
            put("chat_id", "c_42")
            put("sender_id", "u_99")
            put("content", "hello")
            put("timestamp", 1L)
            // type + status omitted
        }

        val raw = subject.mapToRaw(record)

        assertEquals(MessageType.TEXT.name, raw.type)
        assertEquals(MessageStatus.SENT.name, raw.status)
    }

    @Test
    fun `mapToRaw maps empty content to null so plaintext-required check fails closed`() {
        // RawMessage docs: "At least one of [content] or [ciphertext] must be
        // non-null when deletedAt is null". Empty content from PB must surface
        // as null so the repo skips the row instead of treating "" as valid.
        val record = JSONObject().apply {
            put("id", "m_4")
            put("chat_id", "c_42")
            put("sender_id", "u_99")
            put("content", "")
            put("type", MessageType.TEXT.name)
            put("status", MessageStatus.SENT.name)
            put("timestamp", 1L)
        }

        val raw = subject.mapToRaw(record)

        assertNull(raw.content)
    }
}
