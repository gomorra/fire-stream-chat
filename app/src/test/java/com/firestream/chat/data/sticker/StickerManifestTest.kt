package com.firestream.chat.data.sticker

import com.firestream.chat.data.local.entity.StickerEntity
import com.firestream.chat.data.local.entity.StickerPackEntity
import com.firestream.chat.data.local.entity.StickerSyncState
import com.firestream.chat.data.remote.source.RemoteSticker
import com.firestream.chat.data.remote.source.RemoteStickerPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What goes into a manifest, and what of a manifest is let into the library. */
class StickerManifestTest {

    private val first = "a".repeat(64)
    private val second = "b".repeat(64)

    private fun row(id: String, emojis: List<String> = emptyList()) =
        StickerEntity(id, "WEBP", 512, 256, isAnimated = true, emojis = emojis, createdAt = 1L)

    private fun remote(
        name: String = "Cats",
        kind: String = "USER",
        originPackId: String? = null,
        importKey: String? = null,
        stickers: List<RemoteSticker> = emptyList(),
        shownInRow: Boolean? = null,
    ) = RemoteStickerPack(
        id = "p1", ownerId = "uid1", name = name, publisher = " Ana\u0007 ", kind = kind, originPackId = originPackId,
        importKey = importKey, sortOrder = 2, createdAt = 3L, updatedAt = 4L, stickers = stickers, shownInRow = shownInRow,
    )

    private fun remoteSticker(id: String, format: String = "WEBP", width: Int = 512, emojis: List<String> = emptyList()) =
        RemoteSticker(id, format, width, 512, false, emojis)

    @Test
    fun `a manifest carries the pack's fields and its stickers in order, without the ones no file can be named by`() {
        val pack = StickerPackEntity(
            id = "p1", name = "Cats", publisher = "Ana", kind = "INSTALLED", originPackId = "root", importKey = "installed:root",
            sortOrder = 2, createdAt = 3L, updatedAt = 4L,
        )

        val tags = listOf("😺", "🐱", "🐈", "😸", "😹", "😻", "😼", "😽", "🙀")
        val manifest = StickerManifest.of(pack, listOf(row(second, tags), row("not a hash"), row(first)), "uid1")

        assertEquals(listOf("p1", "uid1", "Cats", "Ana", "INSTALLED", "root", "installed:root"), listOf(manifest.id, manifest.ownerId, manifest.name, manifest.publisher, manifest.kind, manifest.originPackId, manifest.importKey))
        assertEquals(listOf(2L, 3L, 4L), listOf(manifest.sortOrder.toLong(), manifest.createdAt, manifest.updatedAt))
        assertEquals(listOf(second, first), manifest.stickers.map { it.id })
        assertEquals("as many as an import keeps", tags.take(8), manifest.stickers.first().emojis)
        assertEquals(
            RemoteSticker(first, "WEBP", 512, 256, true, emptyList(), names = emptyList(), autoNameAllowed = true),
            manifest.stickers.last(),
        )
    }

    @Test
    fun `a manifest carries a sticker's names and both automatic-naming switches, and nothing found automatically`() {
        val pack = StickerPackEntity("p1", "Mine", null, "USER", null, "loose:Mine", 0, 1L, 1L, autoNameAllowed = false)
        val made = row(first, listOf("😺")).copy(
            names = listOf("Grumpy cat", "Montag"),
            autoNameAllowed = false,
            autoNameEn = "Angry cat",
            autoWords = listOf("angry"),
            nameRequestedAt = 5L,
        )

        val manifest = StickerManifest.of(pack, listOf(made, row(second)), "uid1")

        assertEquals(false, manifest.autoNameAllowed)
        assertEquals(
            listOf(
                RemoteSticker(first, "WEBP", 512, 256, true, listOf("😺"), listOf("Grumpy cat", "Montag"), false),
                RemoteSticker(second, "WEBP", 512, 256, true, emptyList(), emptyList(), true),
            ),
            manifest.stickers,
        )
        assertTrue("a build that knows names says so for every sticker", StickerManifest.statesWords(manifest))
    }

    @Test
    fun `names count toward what a manifest may weigh`() {
        val pack = StickerPackEntity("p1", "Big", null, "USER", null, null, 0, 1L, 1L)
        // Eight names of 40 chars that take three bytes each: about 1100 bytes a sticker.
        val names = (0 until 8).map { "€".repeat(39) + it }
        val stickers = (0 until 2000).map { row(it.toString(16).padStart(64, '0')).copy(names = names) }

        val listed = StickerManifest.of(pack, stickers, "uid1").stickers

        val bytes = listed.sumOf { sticker -> 128 + sticker.names.orEmpty().sumOf { it.toByteArray().size + 1 } }
        assertTrue("some are listed", listed.size in 500 until 2000)
        assertTrue("and they fit one document", bytes <= StickerManifest.MAX_LIST_BYTES)
    }

    @Test
    fun `sticker rows from a manifest take its names cleaned and capped, and whether they may be named`() {
        val rows = StickerManifest.stickersOf(
            remote(
                stickers = listOf(
                    remoteSticker(first).copy(
                        names = listOf("  Grumpy\u0000 cat ", "", "grumpy CAT", "x".repeat(90)) + List(9) { "n$it" },
                        autoNameAllowed = false,
                    ),
                    // An entry of an older build says neither.
                    remoteSticker(second),
                )
            ),
            now = 9L,
        )

        assertEquals(listOf("Grumpy cat", "x".repeat(40)) + List(6) { "n$it" }, rows.first().names)
        assertEquals(listOf(false, true), rows.map { it.autoNameAllowed })
        assertEquals(emptyList<String>(), rows.last().names)
        assertTrue("nothing automatic comes from a manifest", rows.all { it.autoNameEn == null && it.autoWords.isEmpty() && it.nameRequestedAt == null })
    }

    @Test
    fun `a sticker entry that does not say whether it may be named takes its pack's answer`() {
        val entries = listOf(remoteSticker(first), remoteSticker(second).copy(autoNameAllowed = true))

        // The maker's pack as a build before names backed it up: its made stickers stay out of naming.
        assertEquals(listOf(false, true), StickerManifest.stickersOf(remote(importKey = "loose:My stickers", stickers = entries), 9L).map { it.autoNameAllowed })
        assertEquals(listOf(true, true), StickerManifest.stickersOf(remote(importKey = "wa:cats", stickers = entries), 9L).map { it.autoNameAllowed })
        assertEquals(
            listOf(false, true),
            StickerManifest.stickersOf(remote(importKey = "wa:cats", stickers = entries).copy(autoNameAllowed = false), 9L).map { it.autoNameAllowed },
        )
    }

    @Test
    fun `a manifest of an older build states no words`() {
        assertFalse(StickerManifest.statesWords(remote(stickers = listOf(remoteSticker(first), remoteSticker(second)))))
        assertTrue(StickerManifest.statesWords(remote(stickers = listOf(remoteSticker(first).copy(names = emptyList())))))
    }

    @Test
    fun `a pack row says whether its stickers may be named, and an older manifest is read by its import key`() {
        assertFalse(StickerManifest.packOf(remote(importKey = "wa:cats").copy(autoNameAllowed = false)).autoNameAllowed)
        assertTrue(StickerManifest.packOf(remote(importKey = "loose:Mine").copy(autoNameAllowed = true)).autoNameAllowed)
        // Without the field: only the sticker maker makes a pack that is found by its name, beside the WhatsApp import.
        assertFalse(StickerManifest.packOf(remote(importKey = "loose:My stickers")).autoNameAllowed)
        assertFalse(StickerManifest.packOf(remote(importKey = "loose:Holiday")).autoNameAllowed)
        assertTrue(StickerManifest.packOf(remote(importKey = "loose:WhatsApp")).autoNameAllowed)
        assertTrue(StickerManifest.packOf(remote(importKey = "wa:com.cats\u0000Cats\u0000Ana")).autoNameAllowed)
        assertTrue(StickerManifest.packOf(remote(importKey = "archive:Holiday\u0000Cleo")).autoNameAllowed)
        assertTrue(StickerManifest.packOf(remote(importKey = null)).autoNameAllowed)
    }

    @Test
    fun `a manifest of stickers with many long tags stops before the document is full`() {
        val pack = StickerPackEntity("p1", "Big", null, "USER", null, null, 0, 1L, 1L)
        // Eight tags of 32 chars that take three bytes each: about 900 bytes a sticker.
        val tags = (0 until 8).map { "‍".repeat(31) + it }
        val stickers = (0 until 2000).map { row(it.toString(16).padStart(64, '0'), tags) }

        val listed = StickerManifest.of(pack, stickers, "uid1").stickers

        val bytes = listed.sumOf { sticker -> 128 + sticker.emojis.sumOf { it.toByteArray().size + 1 } }
        assertTrue("some are listed", listed.size in 500 until 2000)
        assertTrue("and they fit one document", bytes <= StickerManifest.MAX_LIST_BYTES)
        assertEquals("the head of the pack, in order", stickers.take(listed.size).map { it.id }, listed.map { it.id })
    }

    @Test
    fun `a manifest lists no more stickers than one document holds`() {
        val pack = StickerPackEntity("p1", "Big", null, "USER", null, null, 0, 1L, 1L)
        val stickers = (0..StickerManifest.MAX_STICKERS).map { row(it.toString(16).padStart(64, '0')) }

        assertEquals(StickerManifest.MAX_STICKERS, StickerManifest.of(pack, stickers, "uid1").stickers.size)
    }

    @Test
    fun `a pack row from a manifest has a cleaned name and an unknown kind as USER, and is synced`() {
        val pack = StickerManifest.packOf(remote(name = "  Kit\u0000tens\n", kind = "SOMETHING_NEW"))

        assertEquals(listOf("p1", "Kittens", "Ana", "USER"), listOf(pack.id, pack.name, pack.publisher, pack.kind))
        assertEquals(listOf(2L, 3L, 4L), listOf(pack.sortOrder.toLong(), pack.createdAt, pack.updatedAt))
        assertEquals(StickerSyncState.SYNCED.name, pack.syncState)
    }

    @Test
    fun `an import key is kept as it is, separators included, and an origin that is no pack id is dropped`() {
        val key = "wa:com.cats\u0000Cats\u0000Ana"

        assertEquals(key, StickerManifest.packOf(remote(importKey = key)).importKey)
        assertNull(StickerManifest.packOf(remote(importKey = "")).importKey)
        assertNull(StickerManifest.packOf(remote(importKey = "k".repeat(600))).importKey)
        assertEquals("root-1", StickerManifest.packOf(remote(originPackId = "root-1")).originPackId)
        assertNull(StickerManifest.packOf(remote(originPackId = "packs/../x")).originPackId)
    }

    @Test
    fun `a manifest says whether the pack has its own thumbnail, and the row takes it from there`() {
        val pack = StickerPackEntity("p1", "Cats", null, "USER", null, "wa:cats", 0, 1L, 1L, shownInRow = false)

        assertEquals(false, StickerManifest.of(pack, emptyList(), "uid1").shownInRow)
        assertEquals(true, StickerManifest.of(pack.copy(shownInRow = true), emptyList(), "uid1").shownInRow)
        assertFalse(StickerManifest.packOf(remote(shownInRow = false)).shownInRow)
        assertTrue("the owner gave a WhatsApp pack its own thumbnail", StickerManifest.packOf(remote(importKey = "wa:cats", shownInRow = true)).shownInRow)
    }

    @Test
    fun `a manifest without the field gets it from the import key`() {
        assertFalse(StickerManifest.packOf(remote(importKey = "wa:com.cats\u0000Cats\u0000Ana")).shownInRow)
        assertFalse(StickerManifest.packOf(remote(importKey = "loose:WhatsApp")).shownInRow)
        assertTrue(StickerManifest.packOf(remote(importKey = "loose:My stickers")).shownInRow)
        assertTrue(StickerManifest.packOf(remote(importKey = "archive:Holiday\u0000Cleo")).shownInRow)
        assertTrue(StickerManifest.packOf(remote(importKey = "installed:root")).shownInRow)
        assertTrue(StickerManifest.packOf(remote(importKey = null)).shownInRow)
    }

    @Test
    fun `the favourites and the loose stickers are in the row whatever a manifest says`() {
        assertTrue(StickerManifest.packOf(remote(kind = "FAVOURITES", shownInRow = false)).shownInRow)
        assertTrue(StickerManifest.packOf(remote(kind = "SAVED", shownInRow = false)).shownInRow)
    }

    @Test
    fun `sticker rows from a manifest leave out bad ids, repeats and unknown formats, and clamp what they keep`() {
        val rows = StickerManifest.stickersOf(
            remote(
                stickers = listOf(
                    remoteSticker(first, width = 90_000, emojis = listOf("😺", "", "x".repeat(40)) + List(9) { "🐱$it" }),
                    remoteSticker("../../databases/app"),
                    remoteSticker(first),
                    remoteSticker("c".repeat(64), format = "TGS"),
                    remoteSticker(second, width = -5),
                )
            ),
            now = 9L,
        )

        assertEquals(listOf(first, second), rows.map { it.id })
        assertEquals(listOf(StickerFiles.MAX_DIMENSION, 0), rows.map { it.width })
        assertEquals("the empty and the overlong tag are dropped, then eight are kept", listOf("😺") + List(7) { "🐱$it" }, rows.first().emojis)
        assertTrue(rows.all { it.createdAt == 9L && it.remoteUrl == null && it.format == "WEBP" })
    }

    @Test
    fun `a pack id is a short name without a path in it`() {
        assertTrue(StickerManifest.isValidPackId("0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertFalse(StickerManifest.isValidPackId(""))
        assertFalse(StickerManifest.isValidPackId("a/b"))
        assertFalse(StickerManifest.isValidPackId(".."))
        assertFalse(StickerManifest.isValidPackId("x".repeat(65)))
    }
}
