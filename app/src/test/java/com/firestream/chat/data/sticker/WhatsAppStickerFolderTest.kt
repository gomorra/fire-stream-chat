package com.firestream.chat.data.sticker

import com.firestream.chat.domain.model.WhatsAppStickerFile
import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsAppStickerFolderTest {

    private fun file(name: String, size: Long = 20_000, modified: Long = 0) =
        WhatsAppStickerFile(uri = "content://tree/$name", name = name, sizeBytes = size, lastModified = modified)

    @Test
    fun `only sticker files of an acceptable size are listed, newest first`() {
        val listed = WhatsAppStickerFolder.stickersNewestFirst(
            listOf(
                file("old.webp", modified = 10),
                file(".nomedia", size = 0),
                file("new.WEBP", modified = 30),
                file("photo.jpg", modified = 40),
                file("empty.webp", size = 0, modified = 50),
                file("huge.webp", size = StickerFiles.MAX_BYTES + 1L, modified = 60),
                file("mid.webp", modified = 20),
                // WhatsApp's Lottie stickers.
                file("lottie.was", modified = 25),
                file("pack.wastickers", modified = 70),
            )
        )

        assertEquals(listOf("new.WEBP", "lottie.was", "mid.webp", "old.webp"), listed.map { it.name })
    }
}
