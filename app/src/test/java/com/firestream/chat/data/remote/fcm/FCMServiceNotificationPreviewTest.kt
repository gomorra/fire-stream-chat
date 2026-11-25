package com.firestream.chat.data.remote.fcm

import com.firestream.chat.data.repository.DECRYPT_ERROR_PLACEHOLDER
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Test

class FCMServiceNotificationPreviewTest {

    private fun message(
        type: MessageType,
        content: String,
        deletedAt: Long? = null,
    ) = Message(
        id = "m1",
        chatId = "c1",
        senderId = "peer1",
        content = content,
        type = type,
        deletedAt = deletedAt,
    )

    @Test
    fun `uses reconciled decrypted text when push payload omits messageContent`() {
        val text = notificationPreviewText(
            messageType = "TEXT",
            messageContent = null,
            reconciled = message(MessageType.TEXT, "Meet at 7?"),
        )
        assertEquals("Meet at 7?", text)
    }

    @Test
    fun `falls back to generic New message when reconciled is null and payload has no content`() {
        assertEquals(
            "New message",
            notificationPreviewText(messageType = "TEXT", messageContent = null, reconciled = null),
        )
    }

    @Test
    fun `never displays the decrypt-failure placeholder in a notification`() {
        val text = notificationPreviewText(
            messageType = "TEXT",
            messageContent = null,
            reconciled = message(MessageType.TEXT, DECRYPT_ERROR_PLACEHOLDER),
        )
        assertEquals("New message", text)
    }

    @Test
    fun `formats media captions from reconciled message and falls back when blank`() {
        assertEquals(
            "\uD83D\uDCF7 Sunset",
            notificationPreviewText("IMAGE", null, message(MessageType.IMAGE, "Sunset")),
        )
        assertEquals(
            "\uD83D\uDCF7 Photo",
            notificationPreviewText("IMAGE", null, message(MessageType.IMAGE, "")),
        )
        assertEquals(
            "\uD83C\uDFA5 Clip",
            notificationPreviewText("VIDEO", null, message(MessageType.VIDEO, "Clip")),
        )
        assertEquals(
            "\uD83D\uDCCE Report",
            notificationPreviewText("DOCUMENT", null, message(MessageType.DOCUMENT, "Report")),
        )
        assertEquals(
            "\uD83C\uDF99\uFE0F Voice message",
            notificationPreviewText("VOICE", null, message(MessageType.VOICE, "")),
        )
    }

    @Test
    fun `overrideText wins over reconciled content`() {
        val text = notificationPreviewText(
            messageType = "TEXT",
            messageContent = "ignored",
            reconciled = message(MessageType.TEXT, "also ignored"),
            overrideText = "Reacted ❤️ to your message",
        )
        assertEquals("Reacted ❤️ to your message", text)
    }

    @Test
    fun `soft-deleted reconciled message does not leak prior content or FCM fallback`() {
        val text = notificationPreviewText(
            messageType = "TEXT",
            messageContent = "plaintext fallback that must not leak",
            reconciled = message(MessageType.TEXT, "secret", deletedAt = 1234L),
        )
        assertEquals("New message", text)
    }
}
