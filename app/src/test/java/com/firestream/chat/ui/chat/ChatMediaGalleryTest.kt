package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the projection behind the in-chat swipeable image gallery: tapping an
 * image opens a pager over *every* image in the chat, and closing it scrolls
 * back to the message the user swiped to — which only works if the gallery
 * order matches the message list and every entry carries its message id.
 */
class ChatMediaGalleryTest {

    private fun image(
        id: String,
        mediaUrl: String? = "https://example.com/$id.jpg",
        localUri: String? = null,
        deletedAt: Long? = null,
    ) = Message(
        id = id,
        type = MessageType.IMAGE,
        mediaUrl = mediaUrl,
        localUri = localUri,
        deletedAt = deletedAt,
    )

    @Test
    fun `keeps chat order so swiping forward moves forward in time`() {
        val gallery = chatImageGallery(
            listOf(
                image("img1"),
                Message(id = "txt1", type = MessageType.TEXT, content = "hi"),
                image("img2"),
                image("img3"),
            ),
        )

        assertEquals(listOf("img1", "img2", "img3"), gallery.map { it.messageId })
    }

    @Test
    fun `carries both sources so the viewer can resolve local-first`() {
        val gallery = chatImageGallery(
            listOf(image("img1", mediaUrl = "https://example.com/a.jpg", localUri = "/data/a.jpg")),
        )

        assertEquals(
            FullscreenMediaItem(
                imageUrl = "https://example.com/a.jpg",
                localUri = "/data/a.jpg",
                messageId = "img1",
            ),
            gallery.single(),
        )
    }

    @Test
    fun `drops non-image messages`() {
        val gallery = chatImageGallery(
            listOf(
                Message(id = "txt1", type = MessageType.TEXT),
                Message(id = "vid1", type = MessageType.VIDEO, mediaUrl = "https://example.com/v.mp4"),
                Message(id = "voice1", type = MessageType.VOICE, mediaUrl = "https://example.com/v.ogg"),
                image("img1"),
            ),
        )

        assertEquals(listOf("img1"), gallery.map { it.messageId })
    }

    @Test
    fun `drops deleted images`() {
        val gallery = chatImageGallery(listOf(image("img1"), image("img2", deletedAt = 1_000L)))

        assertEquals(listOf("img1"), gallery.map { it.messageId })
    }

    @Test
    fun `drops images with nothing to decode`() {
        val gallery = chatImageGallery(listOf(image("img1", mediaUrl = null, localUri = null)))

        assertTrue(gallery.isEmpty())
    }

    @Test
    fun `keeps an image that is still uploading`() {
        // Local file only — the bubble is on screen and tappable before the
        // upload finishes, so the gallery must be able to show it.
        val gallery = chatImageGallery(
            listOf(image("img1", mediaUrl = null, localUri = "/data/pending.jpg")),
        )

        assertEquals(listOf("img1"), gallery.map { it.messageId })
    }

    @Test
    fun `empty chat yields an empty gallery`() {
        assertTrue(chatImageGallery(emptyList()).isEmpty())
    }
}
