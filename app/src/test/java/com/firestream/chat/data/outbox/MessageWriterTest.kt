package com.firestream.chat.data.outbox

import com.firestream.chat.data.crypto.EncryptedMessage
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
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
    private val preferencesDataStore = mockk<PreferencesDataStore>()

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
        every { preferencesDataStore.e2eEncryptionEnabledFlow } returns flowOf(true)
        coEvery { signalManager.encrypt("peer1", "hello") } returns EncryptedMessage("cipher-1", signalType = 3)
    }

    private fun writer(buildEncrypts: Boolean = true) =
        MessageWriter(messageSource, signalManager, preferencesDataStore, buildEncrypts)

    @Test
    fun `a 1-to-1 message in a build that encrypts is encrypted for its peer, after Signal is initialised`() = runTest {
        val body = writer().encode(text, SendTarget.Peer("peer1"))

        assertEquals(EncryptedMessage("cipher-1", signalType = 3), body)
        coVerifyOrder {
            signalManager.ensureInitialized()
            signalManager.encrypt("peer1", "hello")
        }
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
    fun `a location goes out in plaintext even to a 1-to-1 peer`() = runTest {
        assertPlain(writer().encode(text.copy(type = MessageType.LOCATION), SendTarget.Peer("peer1")))
    }

    @Test
    fun `a user who opted out of end-to-end encryption writes plaintext`() = runTest {
        every { preferencesDataStore.e2eEncryptionEnabledFlow } returns flowOf(false)

        assertPlain(writer().encode(text, SendTarget.Peer("peer1")))
    }

    @Test
    fun `send encrypts and writes the ciphertext under the message id`() = runTest {
        coEvery {
            messageSource.sendMessage(
                chatId = any(), senderId = any(), messageId = any(), ciphertext = any(), signalType = any(),
                type = any(), replyToId = any(), timestamp = any(), mediaUrl = any(), mediaThumbnailUrl = any(),
                isForwarded = any(), duration = any(), mentions = any(), emojiSizes = any(),
                mediaWidth = any(), mediaHeight = any(), latitude = any(), longitude = any(), isHd = any(),
                ifAbsent = any(),
            )
        } returns "msg1"

        val remoteId = writer().send(text, SendTarget.Peer("peer1"))

        assertEquals("msg1", remoteId)
        coVerify(exactly = 1) {
            messageSource.sendMessage(
                chatId = "chat1", senderId = "uid1", messageId = "msg1", ciphertext = "cipher-1", signalType = 3,
                type = MessageType.TEXT, replyToId = null, timestamp = 1_000L, mediaUrl = null,
                mediaThumbnailUrl = null, isForwarded = false, duration = null, mentions = emptyList(),
                emojiSizes = emptyMap(), mediaWidth = null, mediaHeight = null,
                latitude = null, longitude = null, isHd = false, ifAbsent = false,
            )
        }
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
                ifAbsent = true,
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
