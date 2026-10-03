// region: AGENT-NOTE
// Responsibility: The sticker library on this device — packs, their stickers,
//   favourites and recents — and the import of sticker files and pack archives
//   into it. No remote yet: nothing here uploads, and nothing restores.
// Owns: which pack an imported sticker joins (its WhatsApp metadata, an archive's
//   title, else the caller's loose pack or SAVED); the import key that lets a
//   second import find the same pack; the import counts. One import runs at a time.
// Collaborators: StickerDao (rows, and every multi-statement write as one
//   transaction), StickerFiles (the content-addressed files), StickerPackArchive
//   and WaStickerMetadata (untrusted input), WhatsAppStickerFolder (the folder
//   listing), PreferencesDataStore (recents, device-only).
// Don't put here: parsing of a file or an archive (domain/util/WebpContainer,
//   data/sticker/), sending a sticker (MessageRepositoryImpl — "The repository
//   decides who a send is for", docs/PATTERNS.md), anything a screen picks or
//   launches (the composable owns its launchers).
// endregion

package com.firestream.chat.data.repository

import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.local.entity.StickerEntity
import com.firestream.chat.data.local.entity.StickerPackEntity
import com.firestream.chat.data.sticker.StickerFiles
import com.firestream.chat.data.sticker.StickerPackArchive
import com.firestream.chat.data.sticker.StoredSticker
import com.firestream.chat.data.sticker.WaStickerMetadata
import com.firestream.chat.data.sticker.WhatsAppStickerFolder
import com.firestream.chat.data.sticker.cleanStickerText
import com.firestream.chat.data.util.rethrowIfCancellation
import com.firestream.chat.data.util.resultOf
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerFormat
import com.firestream.chat.domain.model.StickerImportResult
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.domain.model.WhatsAppStickerFile
import com.firestream.chat.domain.repository.StickerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StickerRepositoryImpl @Inject constructor(
    private val stickerDao: StickerDao,
    private val stickerFiles: StickerFiles,
    private val whatsAppFolder: WhatsAppStickerFolder,
    private val preferences: PreferencesDataStore,
) : StickerRepository {

    /**
     * Held for a whole import. An archive that breaks a cap deletes the files it
     * had just stored, and a second import running beside it could have counted
     * on one of them.
     */
    private val importLock = Mutex()

    override fun observePacks(): Flow<List<StickerPack>> =
        combine(stickerDao.observePacks(), stickerDao.observePackStickers()) { packs, rows ->
            val stickersByPack = rows.groupBy({ it.packId }, { it.sticker })
            packs.map { pack -> pack.toDomain(stickersByPack[pack.id].orEmpty().mapNotNull { it.toSticker() }) }
        }.distinctUntilChanged()
            // Every emission maps the whole library, which must not happen on the collector's main thread.
            .flowOn(Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeRecents(): Flow<List<Sticker>> =
        preferences.recentStickerIdsFlow.distinctUntilChanged().flatMapLatest { ids ->
            if (ids.isEmpty()) return@flatMapLatest flowOf(emptyList())
            stickerDao.observeStickers(ids).map { found ->
                val byId = found.associateBy { it.id }
                ids.mapNotNull { byId[it]?.toSticker() }
            }
        }.distinctUntilChanged()

    override suspend fun listWhatsAppFolder(treeUri: String): Result<List<WhatsAppStickerFile>> =
        resultOf { whatsAppFolder.list(treeUri) }

    override suspend fun importFrom(uris: List<String>, loosePackName: String?): Result<StickerImportResult> =
        resultOf {
            withContext(Dispatchers.IO) {
                importLock.withLock { import(uris.distinct(), loosePackName?.let(::cleanStickerText)) }
            }
        }

    private suspend fun import(uris: List<String>, loosePackName: String?): StickerImportResult {
        val now = System.currentTimeMillis()
        val found = mutableListOf<Found>()
        var rejected = 0
        for (uri in uris) {
            // Anything a provider or a hostile file throws refuses that one input, not the import.
            try {
                stickerFiles.open(uri).buffered().use { input ->
                    if (StickerPackArchive.isArchive(input)) {
                        rejected += readArchive(input, loosePackName, now, found)
                    } else {
                        val stored = stickerFiles.store(input)
                        if (stored == null) rejected++ else found += found(stored, loosePackName, now)
                    }
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                rejected++
            }
        }

        var imported = 0
        var duplicates = 0
        val packIds = mutableListOf<String>()
        found.groupBy { it.target.importKey }.values.forEach { group ->
            val (packId, added) = stickerDao.importInto(newPack(group.first().target, now), group.map { it.entity }, now)
            imported += added
            duplicates += group.size - added
            if (added > 0) packIds += packId
        }
        return StickerImportResult(imported, duplicates, rejected, packIds)
    }

    /**
     * Stores the archive's stickers and adds them to [found], all under the
     * archive's title when it has one. Returns how many entries were refused.
     * An archive that breaks a cap or is malformed throws, after the files it
     * had just stored are deleted again, so a refused archive leaves nothing.
     */
    private suspend fun readArchive(
        input: BufferedInputStream,
        loosePackName: String?,
        now: Long,
        found: MutableList<Found>,
    ): Int {
        val stored = mutableListOf<Found>()
        var refused = 0
        val summary = try {
            StickerPackArchive.read(input) { bytes ->
                val sticker = stickerFiles.store(bytes)
                if (sticker == null) refused++ else stored += found(sticker, loosePackName, now)
            }
        } catch (e: Exception) {
            val fresh = stored.filter { it.isNew }
            val known = stickerDao.getStickers(fresh.map { it.entity.id }).mapTo(HashSet()) { it.id }
            fresh.filterNot { it.entity.id in known }.forEach { stickerFiles.discard(it.entity.id, it.format) }
            throw e
        }
        val archivePack = summary.title?.let { Target.archive(it, summary.author) }
        found += if (archivePack == null) stored else stored.map { it.copy(target = archivePack) }
        return refused + summary.skipped
    }

    /**
     * The row [stored] becomes and the pack its own metadata sends it to. The
     * EXIF bytes are read here and not kept: an import of a whole folder would
     * otherwise hold every file's chunk until its last file is stored.
     */
    private fun found(stored: StoredSticker, loosePackName: String?, now: Long): Found {
        val metadata = WaStickerMetadata.parse(stored.exif)
        val entity = StickerEntity(
            id = stored.id,
            format = stored.format.name,
            width = stored.width,
            height = stored.height,
            isAnimated = stored.isAnimated,
            emojis = metadata?.emojis.orEmpty(),
            createdAt = now,
        )
        return Found(entity, stored.format, targetFor(metadata, loosePackName), stored.isNew)
    }

    /** The pack a sticker outside a titled archive joins: the one its own metadata names, else the loose pack. */
    private fun targetFor(metadata: WaStickerMetadata?, loosePackName: String?): Target = when {
        metadata != null && (metadata.packId != null || metadata.packName != null) ->
            Target.whatsApp(metadata.packId, metadata.packName, metadata.publisher, loosePackName ?: DEFAULT_PACK_NAME)
        loosePackName != null -> Target.loose(loosePackName)
        else -> Target.SAVED
    }

    override suspend fun setFavourite(stickerId: String, favourite: Boolean): Result<Unit> = resultOf {
        val now = System.currentTimeMillis()
        if (favourite) {
            if (!stickerDao.addFavourite(newPack(Target.FAVOURITES, now), stickerId, now)) {
                throw NoSuchElementException("That sticker is not in the library")
            }
        } else {
            stickerDao.getPackByImportKey(Target.FAVOURITES.importKey)
                ?.let { stickerDao.removeFromPack(it.id, listOf(stickerId), now) }
        }
    }

    override suspend fun renamePack(packId: String, name: String): Result<Unit> = resultOf {
        val cleaned = requireNotNull(cleanStickerText(name)) { "A pack needs a name" }
        val pack = requirePack(packId)
        require(pack.kind == StickerPackKind.USER.name || pack.kind == StickerPackKind.INSTALLED.name) {
            "This pack cannot be renamed"
        }
        stickerDao.renamePack(packId, cleaned, System.currentTimeMillis())
    }

    override suspend fun reorderPacks(packIds: List<String>): Result<Unit> = resultOf {
        stickerDao.reorderPacks(packIds, System.currentTimeMillis())
    }

    override suspend fun deletePack(packId: String): Result<Unit> = resultOf {
        stickerDao.deletePack(packId)
    }

    override suspend fun moveStickers(stickerIds: List<String>, fromPackId: String, toPackId: String): Result<Unit> =
        resultOf {
            if (fromPackId == toPackId) return@resultOf
            if (!stickerDao.moveBetweenPacks(fromPackId, toPackId, stickerIds, System.currentTimeMillis())) {
                throw NoSuchElementException(PACK_GONE)
            }
        }

    override suspend fun removeStickers(packId: String, stickerIds: List<String>): Result<Unit> = resultOf {
        stickerDao.removeFromPack(packId, stickerIds, System.currentTimeMillis())
    }

    override suspend fun markUsed(stickerId: String) {
        if (StickerFiles.isValidId(stickerId)) preferences.addRecentSticker(stickerId)
    }

    private suspend fun requirePack(packId: String): StickerPackEntity =
        stickerDao.getPack(packId) ?: throw NoSuchElementException(PACK_GONE)

    /** `null` for a row whose id is not a hash, which no import writes and no path may be built from. */
    private fun StickerEntity.toSticker(): Sticker? {
        if (!StickerFiles.isValidId(id)) return null
        return toDomain { format -> stickerFiles.fileFor(id, format).absolutePath }
    }

    private fun newPack(target: Target, now: Long) = StickerPackEntity(
        id = UUID.randomUUID().toString(),
        name = target.name,
        publisher = target.publisher,
        kind = target.kind.name,
        originPackId = null,
        importKey = target.importKey,
        sortOrder = 0,
        createdAt = now,
        updatedAt = now,
    )

    /**
     * The pack a sticker is headed for. [importKey] is what finds the pack again,
     * and it is stored: the formats below must not change once packs exist.
     */
    private data class Target(
        val importKey: String,
        val kind: StickerPackKind,
        val name: String,
        val publisher: String?,
    ) {
        companion object {
            private const val SEPARATOR = "\u0000"

            val SAVED = Target("kind:SAVED", StickerPackKind.SAVED, "", null)
            val FAVOURITES = Target("kind:FAVOURITES", StickerPackKind.FAVOURITES, "", null)

            /** The pack a WhatsApp sticker names. Two apps may reuse a pack id, so the name and publisher are part of the key. */
            fun whatsApp(packId: String?, packName: String?, publisher: String?, fallbackName: String) = Target(
                importKey = "wa:" + listOf(packId, packName, publisher).joinToString(SEPARATOR) { it.orEmpty() },
                kind = StickerPackKind.USER,
                name = packName ?: fallbackName,
                publisher = publisher,
            )

            fun archive(title: String, author: String?) =
                Target("archive:$title$SEPARATOR${author.orEmpty()}", StickerPackKind.USER, title, author)

            fun loose(name: String) = Target("loose:$name", StickerPackKind.USER, name, null)
        }
    }

    /** One stored sticker on its way into a pack: its row, where it goes, and whether this import wrote its file. */
    private data class Found(
        val entity: StickerEntity,
        val format: StickerFormat,
        val target: Target,
        val isNew: Boolean,
    )

    private companion object {
        const val DEFAULT_PACK_NAME = "Stickers"
        const val PACK_GONE = "That pack no longer exists"
    }
}
