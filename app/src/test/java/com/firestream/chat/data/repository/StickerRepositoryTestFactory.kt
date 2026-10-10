package com.firestream.chat.data.repository

import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.StickerObjectSource
import com.firestream.chat.data.remote.source.StickerPackSource
import com.firestream.chat.data.sticker.StickerDownloads
import com.firestream.chat.data.sticker.StickerFiles
import com.firestream.chat.data.sticker.StickerLibrarySync
import com.firestream.chat.data.sticker.StickerMaker
import com.firestream.chat.data.sticker.WhatsAppStickerFolder
import com.firestream.chat.data.worker.StickerSyncScheduler
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow

/**
 * A [StickerRepositoryImpl] over the given tables and directory. Everything
 * else is a mock unless a test passes its own: a backend that keeps packs, no
 * restore running, a scheduler that queues nothing, no sticker remembered as
 * deleted, and `uid1` signed in.
 */
internal fun newStickerRepository(
    stickerDao: StickerDao,
    stickerFiles: StickerFiles,
    whatsAppFolder: WhatsAppStickerFolder = mockk(),
    preferences: PreferencesDataStore = mockk {
        coEvery { deletedStickerIds() } returns emptySet()
        coEvery { rememberDeletedStickers(any()) } just Runs
        coEvery { forgetDeletedStickers(any()) } just Runs
    },
    stickerDownloads: StickerDownloads = mockk(),
    stickerObjectSource: StickerObjectSource = mockk(),
    packSource: StickerPackSource = mockk { every { isSupported } returns true },
    librarySync: StickerLibrarySync = mockk { every { whileObserved } returns emptyFlow() },
    syncScheduler: StickerSyncScheduler = mockk(relaxed = true),
    authSource: AuthSource = mockk { every { currentUserId } returns "uid1" },
    stickerMaker: StickerMaker = mockk(),
) = StickerRepositoryImpl(
    stickerDao = stickerDao,
    stickerFiles = stickerFiles,
    whatsAppFolder = whatsAppFolder,
    preferences = preferences,
    stickerDownloads = stickerDownloads,
    stickerObjectSource = stickerObjectSource,
    packSource = packSource,
    librarySync = librarySync,
    syncScheduler = syncScheduler,
    authSource = authSource,
    stickerMaker = stickerMaker,
)
