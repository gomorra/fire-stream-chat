package com.firestream.chat.data.outbox

import com.firestream.chat.data.crypto.EncryptedMessage
import com.firestream.chat.data.crypto.MessageEnvelope
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The encrypt-or-plaintext policy. Unit tests only ever run a debug build, so
 * the build gate is a constructor value here — the one way the encrypted branch
 * is exercised off a device.
 */
class MessageWriterTest {

    private val messageSource = mockk<MessageSource>(relaxed = true)
    private val signalManager = mockk<SignalManager>(relaxed = true)

    private val text = Message(
        id = "msg1",
        chatId = "chat1",
        senderId = "uid1",
        content = "hello",
        type = MessageType.TEXT,
        status = MessageStatus.SENDING,
        timestamp = 1_000L,
    )

    @Before
    fun setUp() {
        coEvery { signalManager.encrypt("peer1", any()) } returns EncryptedMessage("cipher-1", signalType = 3)
    }

    private fun writer(buildEncrypts: Boolean = true) =
        MessageWriter(messageSource, signalManager, buildEncrypts)

    @Test
    fun `a 1-to-1 message in a build that encrypts encodes a v1 envelope for its peer, after Signal is initialised`() = runTest {
        val capturedPayload = slot<String>()
        coEvery { signalManager.encrypt("peer1", capture(capturedPayload)) } returns EncryptedMessage("cipher-1", signalType = 3)

        val body = writer().encode(text, SendTarget.Peer("peer1"))

        assertEquals(EncryptedMessage("cipher-1", signalType = 3), body)
        coVerifyOrder {
            signalManager.ensureInitialized()
            signalManager.encrypt("peer1", any())
        }
        val decoded = MessageEnvelope.decode(capturedPayload.captured)
        assertEquals(MessageEnvelope.CURRENT_VERSION, decoded.v)
        assertEquals(MessageEnvelope.Kind.MSG, decoded.kind)
        assertEquals("hello", decoded.body)
    }

    @Test
    fun `a build that does not encrypt writes plaintext`() = runTest {
        assertPlain(writer(buildEncrypts = false).encode(text, SendTarget.Peer("peer1")))
    }

    @Test
    fun `a group or broadcast message has no peer and goes out in plaintext`() = runTest {
        assertPlain(writer().encode(text, SendTarget.NoPeer))
    }

    @Test
    fun `a 1-to-1 location is encrypted inside the Signal envelope`() = runTest {
        val capturedPayload = slot<String>()
        coEvery { signalManager.encrypt("peer1", capture(capturedPayload)) } returns EncryptedMessage("cipher-loc", signalType = 3)
        val loc = text.copy(
            type = MessageType.LOCATION,
            content = "Home",
            latitude = 52.52,
            longitude = 13.405,
        )

        val body = writer().encode(loc, SendTarget.Peer("peer1"))

        assertEquals(EncryptedMessage("cipher-loc", signalType = 3), body)
        val decoded = MessageEnvelope.decode(capturedPayload.captured)
        assertEquals("Home", decoded.body)
        assertEquals(52.52, decoded.location!!.latitude, 0.0001)
        assertEquals(13.405, decoded.location!!.longitude, 0.0001)
    }

    @Test
    fun `willEncrypt is always true for 1-to-1 targets when buildEncrypts is true, and false otherwise`() {
        assertTrue(writer(buildEncrypts = true).willEncrypt(SendTarget.Peer("peer1")))
        assertFalse(writer(buildEncrypts = true).willEncrypt(SendTarget.NoPeer))
        assertFalse(writer(buildEncrypts = false).willEncrypt(SendTarget.Peer("peer1")))
        assertFalse(writer(buildEncrypts = false).willEncrypt(SendTarget.NoPeer))
    }

    @Test
    fun `send encrypts full metadata inside ciphertext and writes only routing fields to MessageSource`() = runTest {
        val capturedPayload = slot<String>()
        coEvery { signalManager.encrypt("peer1", capture(capturedPayload)) } returns EncryptedMessage("cipher-1", signalType = 3)
        coEvery {
            messageSource.sendMessage(
                chatId = any(), senderId = any(), messageId = any(), ciphertext = any(), signalType = any(),
                type = any(), timestamp = any(), isForwarded = any(), ifAbsent = any(),
            )
        } returns "msg1"

        val richDoc = text.copy(
            type = MessageType.DOCUMENT,
            content = "secret caption",
            replyToId = "prev-id",
            mentions = listOf("peer1"),
            emojiSizes = mapOf(0 to 2.0f),
            mediaUrl = "https://storage.example/media/chat1/msg1.enc",
            mediaKey = "keyB64",
            mediaIv = "ivB64",
            mediaSha256 = "shaB64",
            fileName = "secret.pdf",
            fileSize = 9001L,
            mimeType = "application/pdf",
            isForwarded = true,
        )

        val remoteId = writer().send(richDoc, SendTarget.Peer("peer1"))

        assertEquals("msg1", remoteId)
        coVerify(exactly = 1) {
            messageSource.sendMessage(
                chatId = "chat1",
                senderId = "uid1",
                messageId = "msg1",
                ciphertext = "cipher-1",
                signalType = 3,
                type = MessageType.DOCUMENT,
                timestamp = 1_000L,
                isForwarded = true,
                ifAbsent = false,
            )
        }
        val envelope = MessageEnvelope.decode(capturedPayload.captured)
        assertEquals("secret caption", envelope.body)
        assertEquals("prev-id", envelope.replyToId)
        assertEquals(listOf("peer1"), envelope.mentions)
        assertEquals("secret.pdf", envelope.media?.fileName)
        assertEquals("keyB64", envelope.media?.key)
    }

    @Test
    fun `a plaintext write carries every field of the row`() = runTest {
        val forwardedVideo = text.copy(
            type = MessageType.VIDEO,
            content = "clip",
            mediaUrl = "https://storage.example/v",
            mediaThumbnailUrl = "https://storage.example/v_thumb",
            mediaWidth = 1280,
            mediaHeight = 720,
            duration = 12,
            isForwarded = true,
            isHd = true,
        )

        writer().write(forwardedVideo, encrypted = null, ifAbsent = true)

        coVerify(exactly = 1) {
            messageSource.sendPlainMessage(
                chatId = "chat1", senderId = "uid1", messageId = "msg1", content = "clip",
                type = MessageType.VIDEO, replyToId = null, timestamp = 1_000L,
                mediaUrl = "https://storage.example/v", mediaThumbnailUrl = "https://storage.example/v_thumb",
                isForwarded = true, duration = 12, mentions = emptyList(), emojiSizes = emptyMap(),
                mediaWidth = 1280, mediaHeight = 720, latitude = null, longitude = null, isHd = true,
                file = null, ifAbsent = true,
            )
        }
    }

    @Test
    fun `a stored ciphertext is reusable only while the peer publishes the identity it was encrypted for`() = runTest {
        coEvery { signalManager.isCurrentIdentity("peer1", "id-1") } returns true
        coEvery { signalManager.isCurrentIdentity("peer1", "id-0") } returns false
        val writer = writer()

        assertTrue(writer.isReusable("peer1", EncryptedMessage("c", 3, peerIdentity = "id-1")))
        assertFalse(writer.isReusable("peer1", EncryptedMessage("c", 3, peerIdentity = "id-0")))
        assertFalse("no recorded identity: nothing to compare", writer.isReusable("peer1", EncryptedMessage("c", 3)))
    }

    private fun assertPlain(encrypted: EncryptedMessage?) {
        assertNull(encrypted)
        coVerify(exactly = 0) { signalManager.encrypt(any(), any()) }
    }
}
