package com.firestream.chat.data.crypto

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageEnvelopeTest {

    @Test
    fun `text message round-trips with mentions, emojiSizes, and replyToId`() {
        val original = Message(
            id = "m1",
            chatId = "c1",
            senderId = "u1",
            content = "hello @bob 😀",
            type = MessageType.TEXT,
            replyToId = "m0",
            mentions = listOf("bob", "everyone"),
            emojiSizes = mapOf(11 to 1.8f),
        )

        val encoded = MessageEnvelope.fromMessage(original).encode()
        val decoded = MessageEnvelope.decode(encoded)

        assertEquals(MessageEnvelope.CURRENT_VERSION, decoded.v)
        assertEquals(MessageEnvelope.Kind.MSG, decoded.kind)
        assertEquals("hello @bob 😀", decoded.body)
        assertEquals("m0", decoded.replyToId)
        assertEquals(listOf("bob", "everyone"), decoded.mentions)
        assertEquals(1.8f, decoded.emojiSizes.getValue(11), 0.001f)
        assertNull(decoded.location)
        assertNull(decoded.sticker)
        assertNull(decoded.media)
        assertNull(decoded.thumb)
    }

    @Test
    fun `video message round-trips encrypted media and thumbnail descriptors`() {
        val original = Message(
            id = "v1",
            chatId = "c1",
            senderId = "u1",
            content = "watch this",
            type = MessageType.VIDEO,
            mediaUrl = "https://storage.example/media/c1/v1.enc",
            mediaKey = "bWVkaWFLZXk=",
            mediaIv = "bWVkaWFJdg==",
            mediaSha256 = "bWVkaWFTaGE=",
            mediaWidth = 1920,
            mediaHeight = 1080,
            duration = 42,
            mediaThumbnailUrl = "https://storage.example/media/c1/v1_thumb.enc",
            thumbKey = "dGh1bWJLZXk=",
            thumbIv = "dGh1bWJJdg==",
            thumbSha256 = "dGh1bWJTaGE=",
        )

        val decoded = MessageEnvelope.decode(MessageEnvelope.fromMessage(original).encode())
        val base = Message(
            id = "v1",
            chatId = "c1",
            senderId = "u1",
            type = MessageType.VIDEO,
            status = MessageStatus.SENT,
        )
        val merged = decoded.applyTo(base)

        assertEquals("watch this", merged.content)
        assertEquals("https://storage.example/media/c1/v1.enc", merged.mediaUrl)
        assertEquals("bWVkaWFLZXk=", merged.mediaKey)
        assertEquals("bWVkaWFJdg==", merged.mediaIv)
        assertEquals("bWVkaWFTaGE=", merged.mediaSha256)
        assertEquals(1920, merged.mediaWidth)
        assertEquals(1080, merged.mediaHeight)
        assertEquals(42, merged.duration)
        assertEquals("https://storage.example/media/c1/v1_thumb.enc", merged.mediaThumbnailUrl)
        assertEquals("dGh1bWJLZXk=", merged.thumbKey)
        assertEquals("dGh1bWJJdg==", merged.thumbIv)
        assertEquals("dGh1bWJTaGE=", merged.thumbSha256)
        assertTrue(merged.isEncryptedMedia)
    }

    @Test
    fun `image HD, document, location, and sticker messages round-trip all fields`() {
        val hdImage = Message(
            type = MessageType.IMAGE,
            content = "sunset",
            mediaUrl = "https://storage.example/img.enc",
            mediaKey = "k",
            mediaIv = "iv",
            mediaSha256 = "sha",
            mediaWidth = 4000,
            mediaHeight = 3000,
            isHd = true,
        )
        val mergedImg = MessageEnvelope.decode(MessageEnvelope.fromMessage(hdImage).encode())
            .applyTo(Message(type = MessageType.IMAGE))
        assertTrue(mergedImg.isHd)
        assertEquals(4000, mergedImg.mediaWidth)
        assertEquals(3000, mergedImg.mediaHeight)
        assertEquals("sunset", mergedImg.content)

        val doc = Message(
            type = MessageType.DOCUMENT,
            content = "report",
            mediaUrl = "https://storage.example/doc.enc",
            mediaKey = "dk",
            mediaIv = "div",
            mediaSha256 = "dsha",
            fileName = "quarterly.pdf",
            fileSize = 123_456L,
            mimeType = "application/pdf",
        )
        val mergedDoc = MessageEnvelope.decode(MessageEnvelope.fromMessage(doc).encode())
            .applyTo(Message(type = MessageType.DOCUMENT))
        assertEquals("quarterly.pdf", mergedDoc.fileName)
        assertEquals(123_456L, mergedDoc.fileSize)
        assertEquals("application/pdf", mergedDoc.mimeType)

        val loc = Message(
            type = MessageType.LOCATION,
            content = "Berlin",
            latitude = 52.52,
            longitude = 13.405,
        )
        val mergedLoc = MessageEnvelope.decode(MessageEnvelope.fromMessage(loc).encode())
            .applyTo(Message(type = MessageType.LOCATION))
        assertEquals(52.52, mergedLoc.latitude!!, 0.0001)
        assertEquals(13.405, mergedLoc.longitude!!, 0.0001)

        val sticker = Message(
            type = MessageType.STICKER,
            content = "😺",
            mediaUrl = "https://storage.example/stickers/abc.webp",
            stickerId = "abc",
            stickerPackId = "pack1",
        )
        val mergedSticker = MessageEnvelope.decode(MessageEnvelope.fromMessage(sticker).encode())
            .applyTo(Message(type = MessageType.STICKER))
        assertEquals("😺", mergedSticker.content)
        assertEquals("https://storage.example/stickers/abc.webp", mergedSticker.mediaUrl)
        assertEquals("abc", mergedSticker.stickerId)
        assertEquals("pack1", mergedSticker.stickerPackId)
        assertFalse(mergedSticker.isEncryptedMedia)
    }

    @Test
    fun `edit envelope round-trips kind, body, and emojiSizes`() {
        val encoded = MessageEnvelope.forEdit("corrected text", mapOf(0 to 2.0f)).encode()
        val decoded = MessageEnvelope.decode(encoded)

        assertEquals(MessageEnvelope.Kind.EDIT, decoded.kind)
        assertEquals("corrected text", decoded.body)
        assertEquals(2.0f, decoded.emojiSizes.getValue(0), 0.001f)
    }

    @Test
    fun `decode falls back to plain body for pre-Step-4 ciphertexts and arbitrary JSON`() {
        val plain = MessageEnvelope.decode("just a legacy plain string")
        assertEquals(MessageEnvelope.Kind.MSG, plain.kind)
        assertEquals("just a legacy plain string", plain.body)

        val userJson = MessageEnvelope.decode("""{"hello":"world"}""")
        assertEquals("""{"hello":"world"}""", userJson.body)

        val futureVersion = MessageEnvelope.decode("""{"v":99,"kind":"MSG","body":"x"}""")
        assertEquals("""{"v":99,"kind":"MSG","body":"x"}""", futureVersion.body)

        val brokenJson = MessageEnvelope.decode("{not valid json")
        assertEquals("{not valid json", brokenJson.body)
    }

    @Test
    fun `legacy plain envelope applyTo preserves metadata present on base Firestore message`() {
        val legacyBase = Message(
            id = "old1",
            type = MessageType.IMAGE,
            mediaUrl = "https://storage.example/legacy.jpg",
            replyToId = "prev1",
            mentions = listOf("alice"),
        )
        val merged = MessageEnvelope.decode("legacy caption").applyTo(legacyBase)

        assertEquals("legacy caption", merged.content)
        assertEquals("https://storage.example/legacy.jpg", merged.mediaUrl)
        assertEquals("prev1", merged.replyToId)
        assertEquals(listOf("alice"), merged.mentions)
    }

    @Test
    fun `encode and decode reject non-finite numbers and handle null or mismatched JSON field types safely`() {
        val withBadNumbers = MessageEnvelope(
            body = "hi",
            emojiSizes = mapOf(0 to Float.NaN, 1 to Float.POSITIVE_INFINITY, 2 to 1.5f),
            location = MessageEnvelope.LocationPayload(Double.NaN, 13.4),
        )
        val decoded = MessageEnvelope.decode(withBadNumbers.encode())
        assertEquals("hi", decoded.body)
        assertEquals(mapOf(2 to 1.5f), decoded.emojiSizes)
        assertNull(decoded.location)

        val weirdJson = """{"v":1,"kind":"MSG","body":null,"mentions":["alice",null,42],"media":{"w":"bad","h":100,"hd":"yes"}}"""
        val decodedWeird = MessageEnvelope.decode(weirdJson)
        assertEquals("", decodedWeird.body)
        assertEquals(listOf("alice"), decodedWeird.mentions)
        assertNull(decodedWeird.media?.width)
        assertEquals(100, decodedWeird.media?.height)
        assertFalse(decodedWeird.media?.isHd ?: true)

        val stringVersion = """{"v":"1","kind":"MSG","body":"raw"}"""
        assertEquals(stringVersion, MessageEnvelope.decode(stringVersion).body)
    }
}
