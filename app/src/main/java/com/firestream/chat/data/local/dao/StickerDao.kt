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
import com.firestream.chat.data.local.entity.StickerSyncState
import kotlinx.coroutines.flow.Flow

/**
 * The sticker library's three tables. Every write that changes a pack or its
 * items goes through a method here that also calls [touchPack], so a pack that
 * differs from the backend is always marked for sync.
 *
 * A deleted pack stays as a tombstone row ([StickerSyncState.DELETED]) until
 * the backend's copy is deleted too. The reads the library is built from leave
 * tombstones out. [getPackRow] and [getUnsyncedPacks] are the sync's reads and
 * return them.
 */
@Dao
interface StickerDao {

    // --- Reads ---

    @Query("SELECT * FROM sticker_packs WHERE syncState != 'DELETED' ORDER BY sortOrder ASC, createdAt ASC")
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

    @Query("SELECT * FROM stickers WHERE id = :id")
    suspend fun getSticker(id: String): StickerEntity?

    @Query("SELECT * FROM sticker_packs WHERE id = :packId AND syncState != 'DELETED'")
    suspend fun getPack(packId: String): StickerPackEntity?

    /** The row of [packId] in whatever state, a tombstone included. */
    @Query("SELECT * FROM sticker_packs WHERE id = :packId")
    suspend fun getPackRow(packId: String): StickerPackEntity?

    /** A tombstone has no import key, so this never returns one. */
    @Query("SELECT * FROM sticker_packs WHERE importKey = :importKey")
    suspend fun getPackByImportKey(importKey: String): StickerPackEntity?

    @Query("SELECT id FROM sticker_packs WHERE syncState != 'DELETED' ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getPackIds(): List<String>

    @Query("SELECT stickerId FROM sticker_pack_items WHERE packId = :packId ORDER BY position ASC")
    suspend fun getStickerIds(packId: String): List<String>

    @Query(
        "SELECT s.* FROM sticker_pack_items i INNER JOIN stickers s ON s.id = i.stickerId " +
            "WHERE i.packId = :packId ORDER BY i.position ASC"
    )
    suspend fun getPackStickers(packId: String): List<StickerEntity>

    /** The ids of [stickerIds] that some pack holds. A tombstone has no items, so it holds none. */
    @Query("SELECT DISTINCT stickerId FROM sticker_pack_items WHERE stickerId IN (:stickerIds)")
    suspend fun getStickerIdsInPacks(stickerIds: List<String>): List<String>

    @Query("SELECT DISTINCT packId FROM sticker_pack_items WHERE stickerId IN (:stickerIds)")
    suspend fun getPackIdsHolding(stickerIds: List<String>): List<String>

    /** The packs whose state the backend does not have: changed packs and tombstones. */
    @Query("SELECT * FROM sticker_packs WHERE syncState != 'SYNCED' ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getUnsyncedPacks(): List<StickerPackEntity>

    @Query("SELECT COUNT(*) FROM sticker_packs WHERE syncState != 'SYNCED'")
    suspend fun countUnsyncedPacks(): Int

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

    /** Where the backend holds the sticker's file. A pack does not change by it, so no pack is touched. */
    @Query("UPDATE stickers SET remoteUrl = :remoteUrl WHERE id = :id")
    suspend fun setRemoteUrl(id: String, remoteUrl: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPack(pack: StickerPackEntity)

    /** Writes the whole row, its [StickerPackEntity.syncState] included. For the restore only. */
    @Update
    suspend fun updatePack(pack: StickerPackEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<StickerPackItemEntity>)

    @Query("DELETE FROM sticker_pack_items WHERE packId = :packId AND stickerId IN (:stickerIds)")
    suspend fun deleteItems(packId: String, stickerIds: List<String>): Int

    @Query("DELETE FROM sticker_pack_items WHERE packId = :packId")
    suspend fun deleteItemsOf(packId: String)

    @Query("DELETE FROM sticker_pack_items WHERE stickerId IN (:stickerIds)")
    suspend fun deleteItemsOfStickers(stickerIds: List<String>)

    @Query("DELETE FROM sticker_packs WHERE id = :packId")
    suspend fun deletePackRow(packId: String)

    // The writes below, up to markDeleted, move `updatedAt` strictly forward, so two changes in
    // one millisecond are still two values. None of them touches a tombstone,
    // which would bring a deleted pack back as a pending one.

    @Query(
        "UPDATE sticker_packs SET updatedAt = MAX(:now, updatedAt + 1), syncState = 'PENDING' " +
            "WHERE id = :packId AND syncState != 'DELETED'"
    )
    suspend fun touchPack(packId: String, now: Long)

    @Query(
        "UPDATE sticker_packs SET name = :name, updatedAt = MAX(:now, updatedAt + 1), syncState = 'PENDING' " +
            "WHERE id = :packId AND syncState != 'DELETED'"
    )
    suspend fun renamePack(packId: String, name: String, now: Long)

    @Query(
        "UPDATE sticker_packs SET sortOrder = :sortOrder, updatedAt = MAX(:now, updatedAt + 1), syncState = 'PENDING' " +
            "WHERE id = :packId AND sortOrder != :sortOrder AND syncState != 'DELETED'"
    )
    suspend fun setSortOrder(packId: String, sortOrder: Int, now: Long)

    @Query(
        "UPDATE sticker_packs SET shownInRow = :shown, updatedAt = MAX(:now, updatedAt + 1), syncState = 'PENDING' " +
            "WHERE id = :packId AND shownInRow != :shown AND syncState != 'DELETED'"
    )
    suspend fun setShownInRow(packId: String, shown: Boolean, now: Long)

    /** Turns the row into a tombstone. Its import key is given up, so a new pack can take it. */
    @Query(
        "UPDATE sticker_packs SET syncState = 'DELETED', importKey = NULL, updatedAt = MAX(:now, updatedAt + 1) " +
            "WHERE id = :packId"
    )
    suspend fun markDeleted(packId: String, now: Long)

    /**
     * Marks the pack synced, but only while it is still the state that was
     * uploaded: pending, and with the [updatedAt] the upload read. A pack that
     * changed during its upload stays pending. Returns the rows changed.
     */
    @Query("UPDATE sticker_packs SET syncState = 'SYNCED' WHERE id = :packId AND updatedAt = :updatedAt AND syncState = 'PENDING'")
    suspend fun markSynced(packId: String, updatedAt: Long): Int

    /** Drops a tombstone once the backend's copy is deleted. A row in any other state stays. */
    @Query("DELETE FROM sticker_packs WHERE id = :packId AND syncState = 'DELETED'")
    suspend fun purgeTombstone(packId: String)

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

    /**
     * A made sticker in one transaction: its row and its place at the end of
     * the pack [packId]. Returns false, and writes nothing, when that pack is gone.
     */
    @Transaction
    suspend fun addMadeSticker(packId: String, sticker: StickerEntity, now: Long): Boolean {
        getPack(packId) ?: return false
        mergeStickers(listOf(sticker))
        addToPack(packId, listOf(sticker.id), atFront = false, now = now)
        return true
    }

    /**
     * A new pack with [stickers] in the given order, after every other pack.
     * Returns false, and writes nothing, when a pack with its import key is
     * already there.
     */
    @Transaction
    suspend fun installPack(pack: StickerPackEntity, stickers: List<StickerEntity>): Boolean {
        if (pack.importKey?.let { getPackByImportKey(it) } != null) return false
        mergeStickers(stickers)
        insertPack(pack.copy(sortOrder = (maxSortOrder() ?: -1) + 1))
        insertItems(stickers.toItems(pack.id))
        return true
    }

    /** Returns the stickers it took out that no pack holds afterwards. */
    @Transaction
    suspend fun removeFromPack(packId: String, stickerIds: List<String>, now: Long): List<String> {
        val held = getStickerIds(packId).toSet()
        val removing = stickerIds.distinct().filter { it in held }
        if (removing.isEmpty()) return emptyList()
        deleteItems(packId, removing)
        touchPack(packId, now)
        return inNoPack(removing)
    }

    /** The ids of [stickerIds] that no pack holds, in the order given. */
    suspend fun inNoPack(stickerIds: List<String>): List<String> {
        val held = getStickerIdsInPacks(stickerIds).toSet()
        return stickerIds.distinct().filterNot { it in held }
    }

    /** Takes [stickerIds] out of every pack that holds one of them. Their rows in `stickers` stay. */
    @Transaction
    suspend fun removeFromEveryPack(stickerIds: List<String>, now: Long) {
        val packIds = getPackIdsHolding(stickerIds)
        deleteItemsOfStickers(stickerIds)
        packIds.forEach { touchPack(it, now) }
    }

    /**
     * A new pack holding the stickers of [stickerIds] the library has a row for,
     * in the given order, after every other pack. Returns the stickers it
     * holds. When the library has a row for none of them, that is none, and
     * nothing is written.
     */
    @Transaction
    suspend fun createPack(pack: StickerPackEntity, stickerIds: List<String>): List<String> {
        val known = getStickers(stickerIds).mapTo(HashSet()) { it.id }
        val held = stickerIds.distinct().filter { it in known }
        if (held.isEmpty()) return held
        // Without an import key, so it is always inserted.
        getOrCreatePack(pack)
        insertItems(held.mapIndexed { index, id -> StickerPackItemEntity(pack.id, id, index) })
        return held
    }

    /**
     * Makes one pack of [packIds]. The first keeps its row and takes [name]. The
     * stickers of the others are appended in the order of [packIds], each once,
     * and the others are deleted: as tombstones when [keepTombstones], else outright.
     * Which kinds of pack may be merged is the caller's rule.
     *
     * Returns false, and writes nothing, when one of the packs is gone.
     */
    @Transaction
    suspend fun mergePacks(packIds: List<String>, name: String, keepTombstones: Boolean, now: Long): Boolean {
        val merging = packIds.distinct()
        if (merging.any { getPack(it) == null }) return false
        val target = merging.first()
        renamePack(target, name, now)
        merging.drop(1).forEach { merged ->
            addToPack(target, getStickerIds(merged), atFront = false, now = now)
            if (keepTombstones) deletePack(merged, now) else deletePackNow(merged)
        }
        return true
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
        deleteItems(fromPackId, moving)
        touchPack(fromPackId, now)
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

    /**
     * Takes [stickerId] out of the favourites when it is one, and puts it at
     * their front when it is not. The pack is [candidate] when there is none yet.
     * Read and write are one transaction, so two quick taps flip twice.
     *
     * Returns whether the sticker is a favourite now, or `null` when the library
     * has no such sticker.
     */
    @Transaction
    suspend fun toggleFavourite(candidate: StickerPackEntity, stickerId: String, now: Long): Boolean? {
        val favourites = candidate.importKey?.let { getPackByImportKey(it) }
        if (favourites != null && stickerId in getStickerIds(favourites.id)) {
            deleteItems(favourites.id, listOf(stickerId))
            touchPack(favourites.id, now)
            return false
        }
        return if (addFavourite(candidate, stickerId, now)) true else null
    }

    /** Numbers the packs of [packIds] from zero in that order, and every other pack after them in its current order. */
    @Transaction
    suspend fun reorderPacks(packIds: List<String>, now: Long) {
        val currentOrder = getPackIds()
        val listed = packIds.distinct().filter { it in currentOrder }
        (listed + currentOrder.filterNot { it in listed }).forEachIndexed { index, id -> setSortOrder(id, index, now) }
    }

    /** Deletes the pack's items and leaves the row as a tombstone, for the sync to delete the backend's copy by. */
    @Transaction
    suspend fun deletePack(packId: String, now: Long) {
        deleteItemsOf(packId)
        markDeleted(packId, now)
    }

    /** Deletes the pack outright. For a backend that keeps no packs, where a tombstone would never be collected. */
    @Transaction
    suspend fun deletePackNow(packId: String) {
        deleteItemsOf(packId)
        deletePackRow(packId)
    }

    /**
     * Deletes a pack the user deleted: as a tombstone when [keepTombstone], else
     * outright. Returns the stickers it held that no pack holds afterwards.
     */
    @Transaction
    suspend fun deletePackLocally(packId: String, keepTombstone: Boolean, now: Long): List<String> {
        val stickerIds = getStickerIds(packId)
        if (keepTombstone) deletePack(packId, now) else deletePackNow(packId)
        return inNoPack(stickerIds)
    }

    // --- Sync ---

    /** A pack and its stickers, in order, as one consistent read. `null` when there is no such row. */
    @Transaction
    suspend fun getPackWithStickers(packId: String): Pair<StickerPackEntity, List<StickerEntity>>? {
        val pack = getPackRow(packId) ?: return null
        return pack to getPackStickers(packId)
    }

    /**
     * Brings the backend's copy of a pack into the library. Returns whether
     * anything was written.
     *
     * - **Same id, newer-only.** A row with [remote]'s id takes its fields and its
     *   stickers only when [remote] is later by `updatedAt`. A tombstone never
     *   does: its delete is on the way.
     * - **Same import key, other id.** Two packs were made from one source, or
     *   two favourites packs on two devices. They become one. The older pack
     *   survives, by `createdAt` and then by id, so every device picks the same
     *   one. It gains the other's stickers and the other becomes a tombstone,
     *   which deletes its backend copy.
     * - **Neither.** [remote] is inserted as it is, already synced.
     */
    @Transaction
    suspend fun applyRemotePack(remote: StickerPackEntity, stickers: List<StickerEntity>, now: Long): Boolean {
        val synced = remote.copy(syncState = StickerSyncState.SYNCED.name)
        val local = getPackRow(remote.id)
        if (local != null) {
            if (local.syncState == StickerSyncState.DELETED.name || remote.updatedAt <= local.updatedAt) return false
            // The key is unique. A row cannot take one that another pack holds.
            val keyHolder = remote.importKey?.let { getPackByImportKey(it) }
            val importKey = if (keyHolder == null || keyHolder.id == remote.id) remote.importKey else local.importKey
            mergeStickers(stickers)
            updatePack(synced.copy(importKey = importKey))
            deleteItemsOf(remote.id)
            insertItems(stickers.toItems(remote.id))
            return true
        }

        mergeStickers(stickers)
        val twin = remote.importKey?.let { getPackByImportKey(it) }
        if (twin == null) {
            insertPack(synced)
            insertItems(stickers.toItems(remote.id))
            return true
        }
        if (compareValuesBy(remote, twin, { it.createdAt }, { it.id }) < 0) {
            val twinStickerIds = getStickerIds(twin.id)
            // The tombstone gives the key up before the survivor takes it.
            deletePack(twin.id, now)
            insertPack(synced)
            insertItems(stickers.toItems(remote.id))
            addToPack(remote.id, twinStickerIds, atFront = false, now = now)
        } else {
            addToPack(twin.id, stickers.map { it.id }, atFront = false, now = now)
            insertPack(remote.copy(importKey = null, syncState = StickerSyncState.DELETED.name, updatedAt = now))
        }
        return true
    }

    /**
     * Removes a pack the backend no longer has. Only a synced row goes: a pack
     * with changes of its own stays and is uploaded again. Returns whether a row
     * was removed.
     */
    @Transaction
    suspend fun removeIfSynced(packId: String): Boolean {
        if (getPackRow(packId)?.syncState != StickerSyncState.SYNCED.name) return false
        deletePackNow(packId)
        return true
    }
}

/** These stickers as the items of [packId], in the order given. */
private fun List<StickerEntity>.toItems(packId: String): List<StickerPackItemEntity> =
    mapIndexed { index, sticker -> StickerPackItemEntity(packId, sticker.id, index) }
