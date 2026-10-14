package com.firestream.chat.ui.chat

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardContentRouteTest {

    @Test
    fun `a GIF is sent as a GIF, whatever the case of its type`() {
        assertEquals(KeyboardContentRoute.GIF, keyboardContentRoute("image/gif"))
        assertEquals(KeyboardContentRoute.GIF, keyboardContentRoute("IMAGE/GIF"))
    }

    @Test
    fun `any other picture is sent as a sticker`() {
        listOf("image/webp", "image/png", "image/jpeg", "image/heic").forEach {
            assertEquals(it, KeyboardContentRoute.STICKER, keyboardContentRoute(it))
        }
    }

    @Test
    fun `what is no picture has no route`() {
        listOf(null, "", "video/mp4", "text/plain", "application/x-tgsticker").forEach {
            assertNull(it, keyboardContentRoute(it))
        }
    }

    @Test
    fun `every type the composer names to the keyboard has a route`() {
        assertTrue(KEYBOARD_CONTENT_MIME_TYPES.all { keyboardContentRoute(it) != null })
    }
}

class ComposerAfterKeyboardContentTest {

    @Test
    fun `the one emoji the keyboard offered stickers for is cleared`() {
        listOf("😀", "❤️‍🔥", "👍🏽", " 🐱 ").forEach {
            assertEquals(it, ComposerEdit("", TextRange(0), emptyMap()), composerAfterKeyboardContent(it))
        }
    }

    @Test
    fun `a message being written stays`() {
        listOf("", "hi", "hi 😀", "😀 😀", "2😀").forEach {
            assertNull(it, composerAfterKeyboardContent(it))
        }
    }
}
