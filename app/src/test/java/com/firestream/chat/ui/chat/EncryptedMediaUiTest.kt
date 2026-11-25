package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncryptedMediaUiTest {

    @Test
    fun `encrypted media never exposes remote enc URL via display helpers`() {
        val pendingEncrypted = Message(
            id = "e1",
            type = MessageType.IMAGE,
            mediaUrl = "https://storage.example/media/c1/e1.enc",
            mediaKey = "keyB64",
            mediaIv = "ivB64",
            mediaSha256 = "shaB64",
            localUri = null,
        )
        assertNull(pendingEncrypted.displayMediaSource)
        assertNull(pendingEncrypted.displayRemoteMediaUrl)

        val downloadedEncrypted = pendingEncrypted.copy(localUri = "/storage/emulated/0/Pictures/FireStream Images/e1.jpg")
        assertEquals("/storage/emulated/0/Pictures/FireStream Images/e1.jpg", downloadedEncrypted.displayMediaSource)
        assertNull(downloadedEncrypted.displayRemoteMediaUrl)
    }

    @Test
    fun `encrypted video thumbnail never exposes remote enc thumb URL`() {
        val pendingThumb = Message(
            id = "v1",
            type = MessageType.VIDEO,
            mediaUrl = "https://storage.example/media/c1/v1.enc",
            mediaKey = "mk",
            mediaThumbnailUrl = "https://storage.example/media/c1/v1_thumb.enc",
            thumbKey = "tk",
            localThumbUri = null,
        )
        assertNull(pendingThumb.displayThumbSource)
        assertNull(pendingThumb.displayRemoteThumbUrl)

        val readyThumb = pendingThumb.copy(localThumbUri = "/data/user/0/files/thumbs/v1.jpg")
        assertEquals("/data/user/0/files/thumbs/v1.jpg", readyThumb.displayThumbSource)
        assertNull(readyThumb.displayRemoteThumbUrl)
    }

    @Test
    fun `legacy unencrypted media still falls back to remote URLs`() {
        val legacy = Message(
            id = "old1",
            type = MessageType.VIDEO,
            mediaUrl = "https://storage.example/media/c1/old1.mp4",
            mediaThumbnailUrl = "https://storage.example/media/c1/old1_thumb.jpg",
            localUri = null,
            localThumbUri = null,
        )
        assertEquals("https://storage.example/media/c1/old1.mp4", legacy.displayMediaSource)
        assertEquals("https://storage.example/media/c1/old1.mp4", legacy.displayRemoteMediaUrl)
        assertEquals("https://storage.example/media/c1/old1_thumb.jpg", legacy.displayThumbSource)
        assertEquals("https://storage.example/media/c1/old1_thumb.jpg", legacy.displayRemoteThumbUrl)
    }

    @Test
    fun `chatImageGallery excludes undownloaded encrypted images and omits enc URLs from downloaded ones`() {
        val messages = listOf(
            Message(
                id = "enc-pending",
                type = MessageType.IMAGE,
                mediaUrl = "https://storage.example/media/c1/p.enc",
                mediaKey = "k",
                localUri = null,
            ),
            Message(
                id = "enc-ready",
                type = MessageType.IMAGE,
                mediaUrl = "https://storage.example/media/c1/r.enc",
                mediaKey = "k",
                localUri = "/local/r.jpg",
            ),
            Message(
                id = "plain-remote",
                type = MessageType.IMAGE,
                mediaUrl = "https://storage.example/media/c1/plain.jpg",
                localUri = null,
            ),
        )

        val gallery = chatImageGallery(messages)

        assertEquals(listOf("enc-ready", "plain-remote"), gallery.map { it.messageId })
        assertNull(gallery[0].imageUrl)
        assertEquals("/local/r.jpg", gallery[0].localUri)
        assertEquals("https://storage.example/media/c1/plain.jpg", gallery[1].imageUrl)
    }
}
