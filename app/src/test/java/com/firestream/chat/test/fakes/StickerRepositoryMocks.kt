package com.firestream.chat.test.fakes

import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerFormat
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.domain.repository.StickerRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf

/**
 * A relaxed [StickerRepository] with an empty library, for a test that builds a
 * chat screen's managers and has nothing to say about stickers. The two flows
 * are stubbed, because a relaxed mock's `Flow` is a mock and not a flow.
 */
internal fun emptyStickerRepository(): StickerRepository = mockk(relaxed = true) {
    every { observePacks() } returns flowOf(emptyList())
    every { observeRecents() } returns flowOf(emptyList())
}

/** A library sticker tagged with [emojis]. Its file does not exist. */
internal fun testSticker(id: String, vararg emojis: String) = Sticker(
    id = id,
    format = StickerFormat.WEBP,
    width = 512,
    height = 512,
    isAnimated = false,
    emojis = emojis.toList(),
    localPath = "/stickers/$id.webp",
)

/** A pack named by its [id]. The favourites and the loose stickers have no name, as in the library. */
internal fun testStickerPack(
    id: String,
    kind: StickerPackKind = StickerPackKind.USER,
    vararg stickers: Sticker,
) = StickerPack(
    id = id,
    name = if (kind == StickerPackKind.USER || kind == StickerPackKind.INSTALLED) id else "",
    publisher = null,
    kind = kind,
    originPackId = null,
    stickers = stickers.toList(),
    createdAt = 0,
    updatedAt = 0,
)
