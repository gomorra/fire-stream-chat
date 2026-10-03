package com.firestream.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.firestream.chat.data.local.entity.PackStickerRow
import com.firestream.chat.data.local.entity.StickerEntity
import com.firestream.chat.data.local.entity.StickerPackEntity
import com.firestream.chat.data.local.entity.StickerPackItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * The sticker library's three tables. Every write that changes a pack or its
 * items goes through a method here that also calls [touchPack], so a pack that
 * differs from the backend is always marked for sync.
 */
@Dao
interface StickerDao {

    // --- Reads ---

    @Query("SELECT * FROM sticker_packs ORDER BY sortOrder ASC, createdAt ASC")
    fun observePacks(): Flow<List<StickerPackEntity>>

    /** Every pack's stickers, each pack's in its own order. */
    @Query(
        "SELECT i.packId AS packId, s.* FROM sticker_pack_items i " +
            "INNER JOIN stickers s ON s.id = i.stickerId ORDER BY i.packId ASC, i.position ASC"
    )
    fun observePackStickers(): Flow<List<PackStickerRow>>

    @Query("SELECT * FROM stickers WHERE id IN (:ids)")
    fun observeStickers(ids: List<String>): Flow<List<StickerEntity>>

    @Query("SELECT * FROM stickers WHERE id IN (:ids)")
    suspend fun getStickers(ids: List<String>): List<StickerEntity>

    @Query("SELECT * FROM sticker_packs WHERE id = :packId")
    suspend fun getPack(packId: String): StickerPackEntity?

    @Query("SELECT * FROM sticker_packs WHERE importKey = :importKey")
    suspend fun getPackByImportKey(importKey: String): StickerPackEntity?

    @Query("SELECT id FROM sticker_packs ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getPackIds(): List<String>

    @Query("SELECT stickerId FROM sticker_pack_items WHERE packId = :packId ORDER BY position ASC")
    suspend fun getStickerIds(packId: String): List<String>

    @Query("SELECT MAX(sortOrder) FROM sticker_packs")
    suspend fun maxSortOrder(): Int?

    @Query("SELECT MIN(position) FROM sticker_pack_items WHERE packId = :packId")
    suspend fun minPosition(packId: String): Int?

    @Query("SELECT MAX(position) FROM sticker_pack_items WHERE packId = :packId")
    suspend fun maxPosition(packId: String): Int?

    // --- Single-statement writes ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStickers(stickers: List<StickerEntity>)

    @Update
    suspend fun updateSticker(sticker: StickerEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPack(pack: StickerPackEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<StickerPackItemEntity>)

    @Query("DELETE FROM sticker_pack_items WHERE packId = :packId AND stickerId IN (:stickerIds)")
    suspend fun deleteItems(packId: String, stickerIds: List<String>): Int

    @Query("DELETE FROM sticker_pack_items WHERE packId = :packId")
    suspend fun deleteItemsOf(packId: String)

    @Query("DELETE FROM sticker_packs WHERE id = :packId")
    suspend fun deletePackRow(packId: String)

    @Query("UPDATE sticker_packs SET updatedAt = :now, syncState = 'PENDING' WHERE id = :packId")
    suspend fun touchPack(packId: String, now: Long)

    @Query("UPDATE sticker_packs SET name = :name, updatedAt = :now, syncState = 'PENDING' WHERE id = :packId")
    suspend fun renamePack(packId: String, name: String, now: Long)

    @Query(
        "UPDATE sticker_packs SET sortOrder = :sortOrder, updatedAt = :now, syncState = 'PENDING' " +
            "WHERE id = :packId AND sortOrder != :sortOrder"
    )
    suspend fun setSortOrder(packId: String, sortOrder: Int, now: Long)

    // --- Transactions ---

    /**
     * Inserts the stickers the library does not have yet. A sticker it already
     * has keeps its row, and gains [StickerEntity.emojis] only when it had none.
     */
    @Transaction
    suspend fun mergeStickers(stickers: List<StickerEntity>) {
        val known = getStickers(stickers.map { it.id }).associateBy { it.id }
        insertStickers(stickers.filter { it.id !in known })
        stickers.forEach { sticker ->
            val existing = known[sticker.id] ?: return@forEach
            if (existing.emojis.isEmpty() && sticker.emojis.isNotEmpty()) updateSticker(existing.copy(emojis = sticker.emojis))
        }
    }

    /**
     * The pack [candidate] stands for: the row with its import key when there is
     * one, else [candidate] itself, inserted after every other pack. A candidate
     * without an import key is always inserted.
     */
    @Transaction
    suspend fun getOrCreatePack(candidate: StickerPackEntity): StickerPackEntity {
        candidate.importKey?.let { key -> getPackByImportKey(key) }?.let { return it }
        val created = candidate.copy(sortOrder = (maxSortOrder() ?: -1) + 1)
        insertPack(created)
        return created
    }

    /**
     * Adds the stickers of [stickerIds] the pack does not hold yet, in the given
     * order: before its first sticker when [atFront], else after its last.
     * Returns how many were added.
     */
    @Transaction
    suspend fun addToPack(packId: String, stickerIds: List<String>, atFront: Boolean, now: Long): Int {
        val present = getStickerIds(packId).toSet()
        val fresh = stickerIds.distinct().filterNot { it in present }
        if (fresh.isEmpty()) return 0
        val first = if (atFront) (minPosition(packId) ?: 0) - fresh.size else (maxPosition(packId) ?: -1) + 1
        insertItems(fresh.mapIndexed { index, id -> StickerPackItemEntity(packId, id, first + index) })
        touchPack(packId, now)
        return fresh.size
    }

    /**
     * One imported group in one transaction: the sticker rows, the pack
     * [candidate] stands for, and the memberships. Returns the pack's id and how
     * many stickers it gained.
     */
    @Transaction
    suspend fun importInto(candidate: StickerPackEntity, stickers: List<StickerEntity>, now: Long): Pair<String, Int> {
        mergeStickers(stickers)
        val pack = getOrCreatePack(candidate)
        return pack.id to addToPack(pack.id, stickers.map { it.id }, atFront = false, now = now)
    }

    @Transaction
    suspend fun removeFromPack(packId: String, stickerIds: List<String>, now: Long) {
        if (deleteItems(packId, stickerIds) > 0) touchPack(packId, now)
    }

    /**
     * Moves the stickers of [stickerIds] that [fromPackId] holds to the end of
     * [toPackId]. Returns false, and moves nothing, when [toPackId] does not exist.
     */
    @Transaction
    suspend fun moveBetweenPacks(fromPackId: String, toPackId: String, stickerIds: List<String>, now: Long): Boolean {
        if (getPack(toPackId) == null) return false
        val held = getStickerIds(fromPackId).toSet()
        val moving = stickerIds.filter { it in held }
        if (moving.isEmpty()) return true
        addToPack(toPackId, moving, atFront = false, now = now)
        removeFromPack(fromPackId, moving, now)
        return true
    }

    /**
     * Puts [stickerId] at the front of the favourites pack, which is [candidate]
     * when there is none yet. Returns false, and creates nothing, when the
     * library has no such sticker.
     */
    @Transaction
    suspend fun addFavourite(candidate: StickerPackEntity, stickerId: String, now: Long): Boolean {
        if (getStickers(listOf(stickerId)).isEmpty()) return false
        val favourites = getOrCreatePack(candidate)
        addToPack(favourites.id, listOf(stickerId), atFront = true, now = now)
        return true
    }

    /** Numbers the packs of [packIds] from zero in that order, and every other pack after them in its current order. */
    @Transaction
    suspend fun reorderPacks(packIds: List<String>, now: Long) {
        val currentOrder = getPackIds()
        val listed = packIds.distinct().filter { it in currentOrder }
        (listed + currentOrder.filterNot { it in listed }).forEachIndexed { index, id -> setSortOrder(id, index, now) }
    }

    @Transaction
    suspend fun deletePack(packId: String) {
        deleteItemsOf(packId)
        deletePackRow(packId)
    }
}
