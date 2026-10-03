package com.firestream.chat.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.firestream.chat.data.sticker.StoredSticker
import com.firestream.chat.data.util.parseStickerFormat
import com.firestream.chat.data.util.parseStickerPackKind
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerFormat
import com.firestream.chat.domain.model.StickerPack

/**
 * One sticker file the library knows. [id] is the SHA-256 of its bytes, which is also its file name.
 *
 * [remoteUrl] is where the backend holds the file, and is null until this device
 * has uploaded it or found it there (`StickerObjectSource.ensureUploaded`). It is
 * never taken from a received message: a send would hand that url on to every chat.
 */
@Entity(tableName = "stickers")
data class StickerEntity(
    @PrimaryKey val id: String,
    val format: String,
    val width: Int,
    val height: Int,
    val isAnimated: Boolean,
    val emojis: List<String>,
    val createdAt: Long,
    val remoteUrl: String? = null,
) {
    /** [localPath] says where a file of the sticker's format lives; the row does not know the directory. */
    fun toDomain(localPath: (StickerFormat) -> String): Sticker {
        val format = parseStickerFormat(format)
        return Sticker(
            id = id,
            format = format,
            width = width,
            height = height,
            isAnimated = isAnimated,
            emojis = emojis,
            localPath = localPath(format),
        )
    }

    companion object {
        /** The row of a file `StickerFiles` has just stored, tagged with the [emojis] its metadata names. */
        fun of(stored: StoredSticker, emojis: List<String>, now: Long) = StickerEntity(
            id = stored.id,
            format = stored.format.name,
            width = stored.width,
            height = stored.height,
            isAnimated = stored.isAnimated,
            emojis = emojis,
            createdAt = now,
        )
    }
}

/**
 * One pack. [importKey] names what the pack was made from, so importing the same
 * source again finds this row instead of making a second pack. The `FAVOURITES`
 * and the `SAVED` pack each have a fixed key, and the unique index is what keeps
 * them at one each. A pack the user made by hand has none. [id] cannot serve
 * for that: it is random, because it becomes a backend document id that only
 * this user may write.
 *
 * Every change to a pack or its items sets [syncState] to
 * [StickerSyncState.PENDING] and moves [updatedAt].
 */
@Entity(
    tableName = "sticker_packs",
    indices = [Index(value = ["importKey"], unique = true)],
)
data class StickerPackEntity(
    @PrimaryKey val id: String,
    val name: String,
    val publisher: String?,
    val kind: String,
    val originPackId: String?,
    val importKey: String?,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val syncState: String = StickerSyncState.PENDING.name,
) {
    fun toDomain(stickers: List<Sticker>) = StickerPack(
        id = id,
        name = name,
        publisher = publisher,
        kind = parseStickerPackKind(kind),
        originPackId = originPackId,
        stickers = stickers,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

/** Whether a pack's current state has reached the backend. */
enum class StickerSyncState { PENDING, SYNCED }

/** A sticker's place in a pack. A sticker can be in several packs, and is in each at most once. */
@Entity(
    tableName = "sticker_pack_items",
    primaryKeys = ["packId", "stickerId"],
    indices = [Index("stickerId")],
)
data class StickerPackItemEntity(
    val packId: String,
    val stickerId: String,
    val position: Int,
)

/** A sticker together with the pack it was read for. */
data class PackStickerRow(
    val packId: String,
    @Embedded val sticker: StickerEntity,
)
