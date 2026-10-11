package com.firestream.chat.ui.stickers

import com.firestream.chat.domain.model.StickerAutoName
import com.firestream.chat.test.fakes.testSticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The two lines a list row of the manager shows for a sticker. */
class StickerLabelsTest {

    private val found = StickerAutoName(
        nameEn = "Sleepy sloth",
        nameDe = "Müdes Faultier",
        words = listOf("Faultier", "tired"),
        text = "Gute Nacht",
        emojis = listOf("🦥", "😴"),
    )

    @Test
    fun `the name line has the person's names first, then the German and the English name that were found`() {
        val sticker = testSticker("s").copy(names = listOf("Sid", "Faulpelz"), autoName = found)

        assertEquals("Sid · Faulpelz · Müdes Faultier · Sleepy sloth", sticker.nameLine())
    }

    @Test
    fun `a name that was taken over as the person's own shows once`() {
        val sticker = testSticker("s").copy(names = listOf("Sleepy sloth"), autoName = found)

        assertEquals("Sleepy sloth · Müdes Faultier", sticker.nameLine())
    }

    @Test
    fun `a sticker with only one found name shows that one`() {
        assertEquals("Sloth", testSticker("s").copy(autoName = StickerAutoName(nameEn = "Sloth")).nameLine())
    }

    @Test
    fun `the word line has the emojis of both groups once, then the search words and the text`() {
        val sticker = testSticker("s", "😴").copy(autoName = found)

        assertEquals("😴 🦥 · Faultier, tired · “Gute Nacht”", sticker.wordLine())
    }

    @Test
    fun `a sticker that only has emojis has a word line and no name line`() {
        val sticker = testSticker("s", "😺")

        assertNull(sticker.nameLine())
        assertEquals("😺", sticker.wordLine())
    }

    @Test
    fun `a sticker nothing is known about has neither line`() {
        assertNull(testSticker("s").nameLine())
        assertNull(testSticker("s").wordLine())
    }
}
