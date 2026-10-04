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
    ) = RemoteStickerPack(
        id = "p1", ownerId = "uid1", name = name, publisher = " Ana\u0007 ", kind = kind, originPackId = originPackId,
        importKey = importKey, sortOrder = 2, createdAt = 3L, updatedAt = 4L, stickers = stickers,
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
        assertEquals(RemoteSticker(first, "WEBP", 512, 256, true, emptyList()), manifest.stickers.last())
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
