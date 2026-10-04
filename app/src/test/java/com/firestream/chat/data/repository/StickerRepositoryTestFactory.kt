package com.firestream.chat.data.repository

import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.StickerObjectSource
import com.firestream.chat.data.remote.source.StickerPackSource
import com.firestream.chat.data.sticker.StickerDownloads
import com.firestream.chat.data.sticker.StickerFiles
import com.firestream.chat.data.sticker.StickerLibrarySync
import com.firestream.chat.data.sticker.WhatsAppStickerFolder
import com.firestream.chat.data.worker.StickerSyncScheduler
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow

/**
 * A [StickerRepositoryImpl] over the given tables and directory. Everything
 * else is a mock unless a test passes its own: a backend that keeps packs, no
 * restore running, a scheduler that queues nothing, and `uid1` signed in.
 */
internal fun newStickerRepository(
    stickerDao: StickerDao,
    stickerFiles: StickerFiles,
    whatsAppFolder: WhatsAppStickerFolder = mockk(),
    preferences: PreferencesDataStore = mockk(),
    stickerDownloads: StickerDownloads = mockk(),
    stickerObjectSource: StickerObjectSource = mockk(),
    packSource: StickerPackSource = mockk { every { isSupported } returns true },
    librarySync: StickerLibrarySync = mockk { every { whileObserved } returns emptyFlow() },
    syncScheduler: StickerSyncScheduler = mockk(relaxed = true),
    authSource: AuthSource = mockk { every { currentUserId } returns "uid1" },
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
)
