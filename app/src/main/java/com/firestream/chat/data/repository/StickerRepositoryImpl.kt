// region: AGENT-NOTE
// Responsibility: The sticker library on this device — packs, their stickers,
//   favourites and recents — the import of sticker files and pack archives
//   into it, and the view and install of a pack someone else shared. Every pack
//   change asks for its backup, and observing the packs keeps the restore running.
// Owns: which pack an imported sticker joins (its WhatsApp metadata, an archive's
//   title, else the caller's loose pack or SAVED); the import key that lets a
//   second import find the same pack, and the one an installed copy is found by;
//   the import counts; the fetch of a sticker's file when its row came first.
//   One import runs at a time.
// Collaborators: StickerDao (rows, and every multi-statement write as one
//   transaction), StickerFiles (the content-addressed files), StickerPackArchive
//   and WaStickerMetadata (untrusted input), WhatsAppStickerFolder (the folder
//   listing), PreferencesDataStore (recents, device-only), StickerSyncScheduler
//   (the backup run), StickerLibrarySync (the restore), StickerPackSource and
//   StickerManifest (a viewed pack), StickerObjectSource and StickerDownloads
//   (a missing file, found by its id and checked against it).
// Don't put here: parsing of a file or an archive (domain/util/WebpContainer,
//   data/sticker/), sending a sticker (MessageRepositoryImpl — "The repository
//   decides who a send is for", docs/PATTERNS.md), the upload of a pack
//   (StickerSyncWorker) or the merge of a restored one (StickerDao.applyRemotePack),
//   anything a screen picks or launches (the composable owns its launchers).
// endregion

package com.firestream.chat.data.repository

import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.local.entity.StickerEntity
import com.firestream.chat.data.local.entity.StickerPackEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.StickerObjectSource
import com.firestream.chat.data.remote.source.StickerPackSource
import com.firestream.chat.data.sticker.StickerDownloads
import com.firestream.chat.data.sticker.StickerFiles
import com.firestream.chat.data.sticker.StickerLibrarySync
import com.firestream.chat.data.sticker.StickerManifest
import com.firestream.chat.data.sticker.StickerPackArchive
import com.firestream.chat.data.sticker.StoredSticker
import com.firestream.chat.data.sticker.WaStickerMetadata
import com.firestream.chat.data.sticker.WhatsAppStickerFolder
import com.firestream.chat.data.sticker.cleanStickerText
import com.firestream.chat.data.util.rethrowIfCancellation
import com.firestream.chat.data.util.resultOf
import com.firestream.chat.data.worker.StickerSyncScheduler
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerFormat
import com.firestream.chat.domain.model.StickerImportResult
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.domain.model.StickerPackPreview
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
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StickerRepositoryImpl @Inject constructor(
    private val stickerDao: StickerDao,
    private val stickerFiles: StickerFiles,
    private val whatsAppFolder: WhatsAppStickerFolder,
    private val preferences: PreferencesDataStore,
    private val stickerDownloads: StickerDownloads,
    private val stickerObjectSource: StickerObjectSource,
    private val packSource: StickerPackSource,
    private val librarySync: StickerLibrarySync,
    private val syncScheduler: StickerSyncScheduler,
    private val authSource: AuthSource,
) : StickerRepository {

    /**
     * Held for a whole import. An archive that breaks a cap deletes the files it
     * had just stored, and a second import running beside it could have counted
     * on one of them.
     */
    private val importLock = Mutex()

    /** A grid shows dozens of cells at once, and each may ask for its file. */
    private val fetches = Semaphore(FETCHES_AT_ONCE)

    /**
     * Stickers whose object the backend said it does not hold. A cell asks every
     * time it is shown, and this answer does not change while the app runs.
     */
    private val notOnBackend: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun observePacks(): Flow<List<StickerPack>> = merge(
        combine(stickerDao.observePacks(), stickerDao.observePackStickers()) { packs, rows ->
            val stickersByPack = rows.groupBy({ it.packId }, { it.sticker })
            packs.map { pack -> pack.toDomain(stickersByPack[pack.id].orEmpty().mapNotNull { it.toSticker() }) }
        }.distinctUntilChanged()
            // Every emission maps the whole library, which must not happen on the collector's main thread.
            .flowOn(Dispatchers.Default),
        // Emits nothing. Collecting it is what keeps the restore listening.
        librarySync.whileObserved,
    )

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
            }.also { syncScheduler.syncIfPending() }
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
            // Under the row lock: a sticker received meanwhile (StickerDownloads) may have
            // found one of these files already there. Its row is written under the same
            // lock, so it is either seen here or the file it stores comes after this delete.
            stickerFiles.rowLock.withLock {
                val known = stickerDao.getStickers(fresh.map { it.entity.id }).mapTo(HashSet()) { it.id }
                fresh.filterNot { it.entity.id in known }.forEach { stickerFiles.discard(it.entity.id, it.format) }
            }
            throw e
        }
        val archivePack = summary.title?.let { Target.archive(it, summary.author) }
        found += if (archivePack == null) stored else stored.map { it.copy(target = archivePack) }
        return refused + summary.skipped
    }

    /** The row [stored] becomes and the pack its own metadata sends it to. */
    private fun found(stored: StoredSticker, loosePackName: String?, now: Long): Found {
        val metadata = stored.metadata
        val entity = StickerEntity.of(stored, metadata?.emojis.orEmpty(), now)
        return Found(entity, stored.format, targetFor(metadata, loosePackName), stored.isNew)
    }

    /** The pack a sticker outside a titled archive joins: the one its own metadata names, else the loose pack. */
    private fun targetFor(metadata: WaStickerMetadata?, loosePackName: String?): Target = when {
        metadata != null && (metadata.packId != null || metadata.packName != null) ->
            Target.whatsApp(metadata.packId, metadata.packName, metadata.publisher)
        loosePackName != null -> Target.loose(loosePackName)
        else -> Target.SAVED
    }

    override suspend fun toggleFavourite(stickerId: String): Result<Boolean> = resultOf {
        val now = System.currentTimeMillis()
        val isFavourite = stickerDao.toggleFavourite(newPack(Target.FAVOURITES, now), stickerId, now)
            ?: throw NoSuchElementException("That sticker is not in the library")
        syncScheduler.syncIfPending()
        isFavourite
    }

    override suspend fun renamePack(packId: String, name: String): Result<Unit> = resultOf {
        val cleaned = requireNotNull(cleanStickerText(name)) { "A pack needs a name" }
        val pack = requirePack(packId)
        require(pack.kind == StickerPackKind.USER.name || pack.kind == StickerPackKind.INSTALLED.name) {
            "This pack cannot be renamed"
        }
        stickerDao.renamePack(packId, cleaned, System.currentTimeMillis())
        syncScheduler.syncIfPending()
    }

    override suspend fun reorderPacks(packIds: List<String>): Result<Unit> = resultOf {
        stickerDao.reorderPacks(packIds, System.currentTimeMillis())
        syncScheduler.syncIfPending()
    }

    override suspend fun deletePack(packId: String): Result<Unit> = resultOf {
        // The tombstone is what tells the backup to delete its copy. Without a backup nothing would collect it.
        if (packSource.isSupported) stickerDao.deletePack(packId, System.currentTimeMillis()) else stickerDao.deletePackNow(packId)
        syncScheduler.syncIfPending()
    }

    override suspend fun moveStickers(stickerIds: List<String>, fromPackId: String, toPackId: String): Result<Unit> =
        resultOf {
            if (fromPackId == toPackId) return@resultOf
            if (!stickerDao.moveBetweenPacks(fromPackId, toPackId, stickerIds, System.currentTimeMillis())) {
                throw NoSuchElementException(PACK_GONE)
            }
            syncScheduler.syncIfPending()
        }

    override suspend fun removeStickers(packId: String, stickerIds: List<String>): Result<Unit> = resultOf {
        stickerDao.removeFromPack(packId, stickerIds, System.currentTimeMillis())
        syncScheduler.syncIfPending()
    }

    override suspend fun markUsed(stickerId: String) {
        if (StickerFiles.isValidId(stickerId)) preferences.addRecentSticker(stickerId)
    }

    /**
     * A restored pack, and a pack added from someone else, bring rows whose
     * files are not here. The file is fetched from the object its id names,
     * never from a url a manifest or a message carried, and [StickerDownloads]
     * stores it only when its bytes hash to that id.
     *
     * A cell that scrolls away cancels its own fetch, so a fling through a large
     * pack leaves no queue behind. The row's `remoteUrl` is not written here:
     * `StickerUploads` finds the object again at the sticker's first send.
     */
    override suspend fun ensureFile(sticker: Sticker): Boolean {
        if (!StickerFiles.isValidId(sticker.id) || sticker.id in notOnBackend) return false
        return try {
            withContext(Dispatchers.IO) {
                if (stickerFiles.fileFor(sticker.id, sticker.format).isFile) return@withContext true
                fetches.withPermit {
                    val url = stickerDao.getSticker(sticker.id)?.remoteUrl
                        ?: stickerObjectSource.urlIfPresent(sticker.id, sticker.format.extension)
                    if (url == null) notOnBackend += sticker.id
                    url != null && stickerDownloads.ensureLocal(sticker.id, url) != null
                }
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            false
        }
    }

    override suspend fun viewPack(packId: String): Result<StickerPackPreview> = resultOf {
        // The id comes from a message, and it becomes a document path.
        if (!StickerManifest.isValidPackId(packId)) throw NoSuchElementException(PACK_UNAVAILABLE)
        val remote = packSource.fetchPack(packId) ?: throw NoSuchElementException(PACK_UNAVAILABLE)
        val pack = StickerManifest.packOf(remote)
        val stickers = StickerManifest.stickersOf(remote, System.currentTimeMillis()).mapNotNull { it.toSticker() }
        if (stickers.isEmpty()) throw NoSuchElementException(PACK_UNAVAILABLE)
        // A copy of a copy still names the pack it all started from.
        val rootPackId = pack.originPackId ?: packId
        StickerPackPreview(
            packId = packId,
            rootPackId = rootPackId,
            name = pack.name.ifEmpty { DEFAULT_PACK_NAME },
            publisher = pack.publisher,
            stickers = stickers,
            isInLibrary = remote.ownerId == authSource.currentUserId ||
                stickerDao.getPack(rootPackId) != null ||
                stickerDao.getPackByImportKey(installedKey(rootPackId)) != null,
        )
    }

    override suspend fun installPack(preview: StickerPackPreview): Result<Unit> = resultOf {
        val now = System.currentTimeMillis()
        val pack = StickerPackEntity(
            id = UUID.randomUUID().toString(),
            name = preview.name,
            publisher = preview.publisher,
            kind = StickerPackKind.INSTALLED.name,
            originPackId = preview.rootPackId,
            importKey = installedKey(preview.rootPackId),
            sortOrder = 0,
            createdAt = now,
            updatedAt = now,
        )
        val stickers = preview.stickers.map { sticker ->
            StickerEntity(
                id = sticker.id,
                format = sticker.format.name,
                width = sticker.width,
                height = sticker.height,
                isAnimated = sticker.isAnimated,
                emojis = sticker.emojis,
                createdAt = now,
            )
        }
        // False: a copy is there already, which is what the caller wanted.
        if (stickerDao.installPack(pack, stickers)) syncScheduler.syncIfPending()
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

            /**
             * The pack a WhatsApp sticker names. Two apps may reuse a pack id, so the name and publisher are part of the key.
             * WhatsApp's own Lottie stickers name a pack by a readable id alone (`SchoolDays`), which then serves as the name.
             */
            fun whatsApp(packId: String?, packName: String?, publisher: String?) = Target(
                importKey = "wa:" + listOf(packId, packName, publisher).joinToString(SEPARATOR) { it.orEmpty() },
                kind = StickerPackKind.USER,
                name = packName ?: packId.orEmpty(),
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
        const val PACK_UNAVAILABLE = "This pack is no longer available"
        const val FETCHES_AT_ONCE = 4

        /**
         * The import key of the copy of the pack [rootPackId]. It keeps a pack from
         * being added twice, and it is backed up: the format must not change.
         */
        fun installedKey(rootPackId: String) = "installed:$rootPackId"
    }
}
