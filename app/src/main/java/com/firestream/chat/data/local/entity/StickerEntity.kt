package com.firestream.chat.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.firestream.chat.data.sticker.StoredSticker
import com.firestream.chat.data.util.parseStickerFormat
import com.firestream.chat.data.util.parseStickerPackKind
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerAutoName
import com.firestream.chat.domain.model.StickerFormat
import com.firestream.chat.domain.model.StickerPack

/**
 * One sticker file the library knows. [id] is the SHA-256 of its bytes, which is also its file name.
 *
 * [remoteUrl] is where the backend holds the file, and is null until this device
 * has uploaded it or found it there (`StickerObjectSource.ensureUploaded`). It is
 * never taken from a received message: a send would hand that url on to every chat.
 *
 * [emojis] and [names] are the words of a person, and are backed up with every
 * pack that holds the sticker. The `auto…` columns are the naming service's
 * answer. Nothing a person does writes them, and no pack manifest carries them.
 * [autoNameAllowed] is false for a sticker the service must not be asked about.
 * [nameRequestedAt] is when this device last asked for a name, or last found a
 * record on the backend that holds no usable answer. It is null before the
 * first look. `StickerNameSync` asks again a day after it at the earliest.
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
    val names: List<String> = emptyList(),
    val autoNameEn: String? = null,
    val autoNameDe: String? = null,
    val autoWords: List<String> = emptyList(),
    val autoText: String? = null,
    val autoEmojis: List<String> = emptyList(),
    val autoNameAllowed: Boolean = true,
    val nameRequestedAt: Long? = null,
) {
    /** What the naming service found, or `null` while the `auto…` columns hold nothing. */
    val autoName: StickerAutoName?
        get() = StickerAutoName(
            nameEn = autoNameEn.orEmpty(),
            nameDe = autoNameDe.orEmpty(),
            words = autoWords,
            text = autoText.orEmpty(),
            emojis = autoEmojis,
        ).takeUnless { it.isEmpty }

    /** This row with [found] in its `auto…` columns. Nothing a person gave the sticker changes. */
    fun withAutoName(found: StickerAutoName): StickerEntity = copy(
        autoNameEn = found.nameEn.ifEmpty { null },
        autoNameDe = found.nameDe.ifEmpty { null },
        autoWords = found.words,
        autoText = found.text.ifEmpty { null },
        autoEmojis = found.emojis,
    )

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
            names = names,
            autoName = autoName,
            autoNameAllowed = autoNameAllowed,
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
 * [StickerSyncState.PENDING] and moves [updatedAt] forward, never back and never
 * to the value it had. `StickerSyncWorker` marks a pack synced only while
 * [updatedAt] is still what it uploaded, and a restore applies a backend copy
 * only when that copy's [updatedAt] is later.
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
    /** Whether the pack has its own thumbnail in the picker's row. See [StickerPack.shownInRow]. */
    val shownInRow: Boolean = true,
    /** Whether the naming service may be asked about the pack's stickers. See [StickerPack.autoNameAllowed]. */
    val autoNameAllowed: Boolean = true,
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
        shownInRow = shownInRow,
        autoNameAllowed = autoNameAllowed,
    )

    companion object {
        /** The start of the import key of a pack that a sticker's WhatsApp metadata names. */
        const val WHATSAPP_KEY_PREFIX = "wa:"

        /** The start of the import key of a pack that is found by its name alone. */
        const val LOOSE_KEY_PREFIX = "loose:"

        /** The import key of the pack that WhatsApp stickers without pack metadata join. */
        const val LOOSE_WHATSAPP_KEY = LOOSE_KEY_PREFIX + "WhatsApp"

        /**
         * What [shownInRow] starts as for a pack with [importKey]: false for a
         * pack a WhatsApp import made, true for every other. A manifest without
         * the field is read by the same rule.
         */
        fun shownInRowByDefault(importKey: String?): Boolean =
            importKey == null || !(importKey.startsWith(WHATSAPP_KEY_PREFIX) || importKey == LOOSE_WHATSAPP_KEY)

        /**
         * What [autoNameAllowed] is for a pack with [importKey] whose manifest
         * does not say: false for a pack the sticker maker created, true for
         * every other. Only the maker and the WhatsApp import make a pack that
         * is found by its name. A manifest of a build before names does not
         * tell a made sticker from an imported one, so this is all a restore
         * of such a manifest has to go by.
         */
        fun autoNameAllowedByDefault(importKey: String?): Boolean =
            importKey == null || !importKey.startsWith(LOOSE_KEY_PREFIX) || importKey == LOOSE_WHATSAPP_KEY
    }
}

/** Whether a pack's current state has reached the backend. */
enum class StickerSyncState {
    /** The row differs from the backend's copy, or the backend has none yet. */
    PENDING,

    SYNCED,

    /**
     * A deleted pack, kept as a tombstone until the backend's copy is deleted
     * too. It has no items and no import key, and no query of the library returns it.
     */
    DELETED,
}

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

/**
 * What a naming run reads of a sticker it may ask about. Not the whole row: a
 * run reads the candidates again before every batch, and a row carries four
 * lists that would be decoded each time.
 */
data class StickerNameCandidate(
    val id: String,
    val format: String,
    val nameRequestedAt: Long?,
)

/** A sticker together with the pack it was read for. */
data class PackStickerRow(
    val packId: String,
    @Embedded val sticker: StickerEntity,
)
