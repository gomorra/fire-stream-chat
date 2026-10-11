package com.firestream.chat.data.sticker

import com.firestream.chat.domain.model.StickerAutoName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What of the naming service's answer is let into a row. A model wrote it, so it is taken whole or not at all. */
class StickerNameAnswerTest {

    private val good: Map<String, Any?> = mapOf(
        "status" to "done",
        "nameEn" to "Sleepy bear",
        "nameDe" to "Müder Bär",
        "words" to listOf("tired", "Schlaf"),
        "text" to "Gute Nacht",
        "emojis" to listOf("😴", "🐻"),
        "model" to "gemma4:26b",
    )

    @Test
    fun `an answer inside the limits is taken as it is`() {
        assertEquals(
            StickerAutoName("Sleepy bear", "Müder Bär", listOf("tired", "Schlaf"), "Gute Nacht", listOf("😴", "🐻")),
            StickerNameAnswer.of(good),
        )
    }

    @Test
    fun `an answer at every limit passes`() {
        val full = good + mapOf(
            "nameEn" to "n".repeat(StickerNameAnswer.MAX_NAME_LENGTH),
            "text" to "t".repeat(StickerNameAnswer.MAX_TEXT_LENGTH),
            "words" to List(StickerNameAnswer.MAX_WORDS) { "w$it".padEnd(StickerNameAnswer.MAX_WORD_LENGTH, 'x') },
            "emojis" to listOf("😴", "🐻", "💤"),
        )

        val found = StickerNameAnswer.of(full)!!

        assertEquals(StickerNameAnswer.MAX_NAME_LENGTH, found.nameEn.length)
        assertEquals(StickerNameAnswer.MAX_WORDS, found.words.size)
        assertEquals(3, found.emojis.size)
    }

    @Test
    fun `one field that breaks a limit drops the whole answer`() {
        val broken: List<Pair<String, Pair<String, Any?>>> = listOf(
            "a name that is too long" to ("nameDe" to "n".repeat(StickerNameAnswer.MAX_NAME_LENGTH + 1)),
            "a text that is too long" to ("text" to "t".repeat(StickerNameAnswer.MAX_TEXT_LENGTH + 1)),
            "a word that is too long" to ("words" to listOf("ok", "w".repeat(StickerNameAnswer.MAX_WORD_LENGTH + 1))),
            "too many words" to ("words" to List(StickerNameAnswer.MAX_WORDS + 1) { "w$it" }),
            "too many emojis" to ("emojis" to listOf("😴", "🐻", "💤", "🌙")),
            "words that are no list" to ("words" to "tired, Schlaf"),
            "emojis that are no list" to ("emojis" to "😴"),
            "a word that is no string" to ("words" to listOf("tired", 7)),
            "a name that is no string" to ("nameEn" to 7),
            "a text that is a list" to ("text" to listOf("Gute Nacht")),
            "an emoji that is a word" to ("emojis" to listOf("😴", "bear")),
            "an emoji that is a word with an umlaut" to ("emojis" to listOf("Bär")),
            "an emoji that is two words" to ("emojis" to listOf("😴 🐻")),
            "an emoji made of punctuation" to ("emojis" to listOf(":-)")),
            "an emoji made of digits" to ("emojis" to listOf("123")),
        )

        broken.forEach { (what, field) ->
            assertNull(what, StickerNameAnswer.of(good + field))
        }
    }

    @Test
    fun `control characters are taken out, and blank and repeated entries dropped`() {
        val found = StickerNameAnswer.of(
            good + mapOf(
                "nameEn" to "  Sleepy\u0000 bear\n",
                "words" to listOf("tired", " ", "tired", "\u0007Schlaf"),
                "emojis" to listOf("😴", "😴"),
            )
        )!!

        assertEquals("Sleepy bear", found.nameEn)
        assertEquals(listOf("tired", "Schlaf"), found.words)
        assertEquals(listOf("😴"), found.emojis)
    }

    @Test
    fun `an absent field reads as empty, and an answer that holds nothing is no answer`() {
        assertEquals(StickerAutoName(nameDe = "Müder Bär"), StickerNameAnswer.of(mapOf("status" to "done", "nameDe" to "Müder Bär")))
        assertEquals(StickerAutoName(text = "Gute Nacht"), StickerNameAnswer.of(mapOf("text" to "Gute Nacht", "words" to null)))
        assertNull(StickerNameAnswer.of(mapOf("status" to "done")))
        assertNull(StickerNameAnswer.of(mapOf("nameEn" to " ", "words" to emptyList<String>())))
    }

    @Test
    fun `an emoji of several code points, a keycap, a flag and the information sign are emojis`() {
        val emojis = listOf("❤️‍🔥", "1️⃣", "🇩🇪")

        assertEquals(emojis, StickerNameAnswer.of(good + ("emojis" to emojis))!!.emojis)
        assertEquals(listOf("ℹ️"), StickerNameAnswer.of(good + ("emojis" to listOf("ℹ️")))!!.emojis)
    }
}
