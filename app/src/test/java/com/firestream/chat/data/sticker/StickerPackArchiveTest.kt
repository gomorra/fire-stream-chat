package com.firestream.chat.data.sticker

import com.firestream.chat.data.sticker.StickerPackArchive.Limits
import com.firestream.chat.test.LottieFixtures.animation
import com.firestream.chat.test.LottieFixtures.tgs
import com.firestream.chat.test.LottieFixtures.was
import com.firestream.chat.test.WebpFixtures.sticker
import com.firestream.chat.test.WebpFixtures.zip
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pack archive is read entry by entry under three caps, and its entry names never become paths. */
class StickerPackArchiveTest {

    private suspend fun read(archive: ByteArray, limits: Limits = Limits()): Pair<StickerPackArchive.Summary, List<ByteArray>> {
        val stickers = mutableListOf<ByteArray>()
        val summary = StickerPackArchive.read(archive.inputStream(), limits) { stickers += it }
        return summary to stickers
    }

    private fun readBlocking(archive: ByteArray, limits: Limits, into: MutableList<ByteArray> = mutableListOf()) =
        kotlinx.coroutines.runBlocking { StickerPackArchive.read(archive.inputStream(), limits) { into += it } }

    @Test
    fun `stickers come out in archive order with the title and author`() = runTest {
        val first = sticker(1)
        val second = sticker(2)
        val archive = zip(
            "01.webp" to first,
            "title.txt" to "  Cats\n".toByteArray(),
            "tray.png" to ByteArray(300),
            "02.WEBP" to second,
            "author.txt" to "Ana".toByteArray(),
        )

        val (summary, stickers) = read(archive)

        assertEquals("Cats", summary.title)
        assertEquals("Ana", summary.author)
        assertEquals(0, summary.skipped)
        assertEquals(2, stickers.size)
        assertArrayEquals(first, stickers[0])
        assertArrayEquals(second, stickers[1])
    }

    @Test
    fun `an entry name with a path decides only what the entry is`() = runTest {
        val bytes = sticker(3)
        val archive = zip(
            "../../../data/evil.webp" to bytes,
            "pack\\nested\\title.txt" to "Deep".toByteArray(),
            "folder/" to ByteArray(0),
        )

        val (summary, stickers) = read(archive)

        assertEquals("Deep", summary.title)
        assertArrayEquals(bytes, stickers.single())
    }

    @Test
    fun `an archive without title and author has neither`() = runTest {
        val (summary, stickers) = read(zip("a.webp" to sticker(4)))

        assertNull(summary.title)
        assertNull(summary.author)
        assertEquals(1, stickers.size)
    }

    @Test
    fun `a title that is too long to be a title is ignored, and a long one is capped`() = runTest {
        val (huge, _) = read(zip("1.webp" to sticker(1), "title.txt" to ByteArray(5_000) { 'a'.code.toByte() }))
        val (long, _) = read(zip("1.webp" to sticker(1), "title.txt" to ByteArray(600) { 'b'.code.toByte() }))

        assertNull(huge.title)
        assertEquals(128, long.title!!.length)
    }

    @Test
    fun `more entries than the cap refuses the archive`() {
        val archive = zip("1.webp" to sticker(1), "2.webp" to sticker(2), "3.webp" to sticker(3))

        val error = assertThrows(StickerArchiveException::class.java) { readBlocking(archive, Limits(maxEntries = 2)) }

        assertTrue(error.message!!.contains("2 entries"))
    }

    @Test
    fun `a sticker entry over the per-entry cap is skipped and the rest are read`() = runTest {
        val small = sticker(1)
        val big = sticker(2, fillerBytes = 4_000)
        val archive = zip("big.webp" to big, "small.webp" to small)

        val (summary, stickers) = read(archive, Limits(maxEntryBytes = 1_000))

        assertEquals(1, summary.skipped)
        assertArrayEquals(small, stickers.single())
    }

    @Test
    fun `an archive that unpacks past the total cap is refused`() {
        val archive = zip("1.webp" to sticker(1, fillerBytes = 3_000), "2.webp" to sticker(2, fillerBytes = 3_000))
        val handed = mutableListOf<ByteArray>()

        assertThrows(StickerArchiveException::class.java) { readBlocking(archive, Limits(maxTotalBytes = 5_000), handed) }

        assertEquals("the first sticker fits the budget and was handed over before the throw", 1, handed.size)
    }

    @Test
    fun `entries that are skipped still count toward the total cap`() {
        // A zip bomb hides in an entry nobody wants: highly compressible, never kept.
        val archive = zip("tray.png" to ByteArray(200_000), "1.webp" to sticker(1))

        assertThrows(StickerArchiveException::class.java) { readBlocking(archive, Limits(maxTotalBytes = 50_000)) }
    }

    @Test
    fun `input that is not a zip is refused`() {
        assertThrows(StickerArchiveException::class.java) { readBlocking(sticker(1), Limits()) }
        assertThrows(StickerArchiveException::class.java) { readBlocking(ByteArray(0), Limits()) }
    }

    @Test
    fun `a zip that holds no sticker is refused`() {
        val document = zip("word/document.xml" to ByteArray(40), "title.txt" to "Report".toByteArray())

        assertThrows(StickerArchiveException::class.java) { readBlocking(document, Limits()) }
    }

    @Test
    fun `a was file hands over its animation and nothing else`() = runTest {
        val json = animation(1)

        val (summary, stickers) = read(was(json))

        assertNull(summary.title)
        assertEquals(0, summary.skipped)
        assertArrayEquals(json, stickers.single())
    }

    @Test
    fun `a tgs entry in a pack is handed over like a WebP`() = runTest {
        val lottie = tgs(animation(2))
        val webp = sticker(1)

        val (_, stickers) = read(zip("a.tgs" to lottie, "b.webp" to webp, "title.txt" to "Mixed".toByteArray()))

        assertArrayEquals(lottie, stickers[0])
        assertArrayEquals(webp, stickers[1])
    }

    @Test
    fun `an animation may be larger than a sticker file, up to its own cap`() = runTest {
        val json = animation(3, extra = ""","pad":"${"a".repeat(3_000)}"""")
        val limits = Limits(maxEntryBytes = 1_000, maxAnimationBytes = 5_000)

        val (kept, stickers) = read(was(json), limits)
        val (cut, none) = read(was(json), limits.copy(maxAnimationBytes = 2_000))

        assertEquals(0, kept.skipped)
        assertArrayEquals(json, stickers.single())
        assertEquals(1, cut.skipped)
        assertTrue(none.isEmpty())
    }

    @Test
    fun `a zip with some other JSON file is not a sticker archive`() {
        val archive = zip("contents.json" to animation(), "manifest.json" to "{}".toByteArray())

        assertThrows(StickerArchiveException::class.java) { readBlocking(archive, Limits()) }
    }

    @Test
    fun `an entry name that is not UTF-8 refuses the archive as an archive error`() {
        // The JDK reports such a name with IllegalArgumentException, which no IOException handler sees.
        val archive = zip(Charsets.ISO_8859_1, "1.webp" to sticker(1), "café.webp" to sticker(2))
        val handed = mutableListOf<ByteArray>()

        assertThrows(StickerArchiveException::class.java) { readBlocking(archive, Limits(), handed) }

        assertEquals(1, handed.size)
    }

    @Test
    fun `an archive is told from a sticker by its first bytes, and the stream is left at its start`() {
        val archive = zip("1.webp" to sticker(1)).inputStream().buffered()

        assertTrue(StickerPackArchive.isArchive(archive))
        assertEquals('P'.code, archive.read())
        assertEquals(false, StickerPackArchive.isArchive(sticker(1).inputStream().buffered()))
        assertEquals(false, StickerPackArchive.isArchive(byteArrayOf('P'.code.toByte(), 'K'.code.toByte()).inputStream().buffered()))
    }
}
