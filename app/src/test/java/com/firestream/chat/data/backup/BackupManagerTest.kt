package com.firestream.chat.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.entity.ChatEntity
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.MessageSyncStateEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.data.worker.BackupScheduler
import com.firestream.chat.domain.model.BackupFrequency
import com.firestream.chat.domain.model.BackupStage
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.RestoreStage
import com.firestream.chat.domain.model.UnsupportedBackupVersionException
import com.firestream.chat.domain.model.WrongPassphraseException
import com.firestream.chat.domain.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class BackupManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dbName: String
    private lateinit var appDatabase: AppDatabase
    private lateinit var backupSource: InMemoryBackupSource
    private lateinit var backupKeyStore: BackupKeyStore
    private lateinit var preferencesDataStore: PreferencesDataStore
    private val authSource = mockk<AuthSource>()
    private val messageRepository = mockk<MessageRepository>(relaxed = true)
    private val mediaFileManager = mockk<MediaFileManager>(relaxed = true)
    private val backupScheduler = mockk<BackupScheduler>(relaxed = true)
    private var nowMs = 1_750_000_000_000L

    private lateinit var tempFiles: MutableList<File>

    @Before
    fun setUp() {
        dbName = "test_backup_${UUID.randomUUID()}.db"
        appDatabase = openRoomDb(dbName)
        backupSource = InMemoryBackupSource()
        backupKeyStore = BackupKeyStore(context, InMemoryKeyWrapper())
        preferencesDataStore = PreferencesDataStore(context)
        every { authSource.currentUserId } returns "uid-alice"
        tempFiles = mutableListOf()
    }

    @After
    fun tearDown() = runTest {
        if (appDatabase.isOpen) appDatabase.close()
        backupKeyStore.clear()
        preferencesDataStore.clearBackupPreferences()
        context.deleteDatabase(dbName)
        tempFiles.forEach { it.delete() }
    }

    private fun openRoomDb(name: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    private fun newManager(db: AppDatabase = appDatabase): BackupManager = BackupManager(
        context = context,
        appDatabase = db,
        messageDao = db.messageDao(),
        chatDao = db.chatDao(),
        messageRepository = messageRepository,
        mediaFileManager = mediaFileManager,
        backupSource = backupSource,
        backupKeyStore = backupKeyStore,
        preferencesDataStore = preferencesDataStore,
        authSource = authSource,
        backupScheduler = backupScheduler,
        kdfIterations = 1_000,
        minManifestIterations = 1_000,
        databaseName = dbName,
        clock = { nowMs },
        legacyUploadRetryDelayMs = 0L,
    )

    @Test
    fun `full backup, wipe, and restore preserves chats, messages, crypto keys, stars, sync state, and legacy media`() = runTest {
        val manager = newManager()
        assertTrue(manager.configurePassphrase("my-strong-backup-passphrase").isSuccess)

        // Seed chats, sync state, text, starred, Phase 2 encrypted media, legacy media, Klipy GIF, and sticker.
        val legacyLocalFile = File(context.cacheDir, "legacy_photo_${UUID.randomUUID()}.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte()))
            tempFiles += this
        }
        val missingLegacyDownloadedFile = File(context.cacheDir, "legacy_doc_${UUID.randomUUID()}.pdf").apply {
            writeText("legacy pdf body")
            tempFiles += this
        }
        val vanishedE2eeFile = File(context.cacheDir, "vanished_e2ee_${UUID.randomUUID()}.jpg") // intentionally non-existent

        appDatabase.chatDao().insertChat(
            ChatEntity(
                id = "chat-1",
                type = ChatType.INDIVIDUAL.name,
                name = "Bob",
                avatarUrl = null,
                participants = listOf("uid-alice", "uid-bob"),
                unreadCount = 0,
                createdAt = 1_000L,
                createdBy = "uid-alice",
                admins = emptyList(),
                lastMessageId = "m-e2ee",
                lastMessageContent = "Encrypted caption",
                lastMessageTimestamp = 4_000L,
                isPinned = true,
                isArchived = false,
            )
        )
        appDatabase.messageSyncStateDao().writeRestored("chat-1", generation = 2, cursorMs = 4_000L)

        // 1. Starred text message
        appDatabase.messageDao().insertOutbox(
            MessageEntity.fromDomain(
                Message(
                    id = "m-text",
                    chatId = "chat-1",
                    senderId = "uid-bob",
                    content = "Hello from decrypted history!",
                    type = MessageType.TEXT,
                    status = MessageStatus.READ,
                    timestamp = 1_000L,
                    isStarred = true,
                )
            )
        )
        // 2. Legacy plaintext photo already on disk
        appDatabase.messageDao().insertOutbox(
            MessageEntity.fromDomain(
                Message(
                    id = "m-legacy-local",
                    chatId = "chat-1",
                    senderId = "uid-alice",
                    content = "old photo",
                    type = MessageType.IMAGE,
                    mediaUrl = "https://storage.example/media/chat-1/m-legacy-local.jpg",
                    localUri = legacyLocalFile.absolutePath,
                    status = MessageStatus.SENT,
                    timestamp = 2_000L,
                )
            )
        )
        // 3. Legacy plaintext document not yet downloaded locally (BackupManager should download it)
        appDatabase.messageDao().insertOutbox(
            MessageEntity.fromDomain(
                Message(
                    id = "m-legacy-remote",
                    chatId = "chat-1",
                    senderId = "uid-bob",
                    content = "",
                    type = MessageType.DOCUMENT,
                    mediaUrl = "https://storage.example/media/chat-1/m-legacy-remote.pdf",
                    fileName = "invoice.pdf",
                    mimeType = "application/pdf",
                    localUri = null,
                    status = MessageStatus.DELIVERED,
                    timestamp = 2_500L,
                )
            )
        )
        coEvery {
            mediaFileManager.downloadFor(
                chatId = "chat-1",
                messageId = "m-legacy-remote",
                type = MessageType.DOCUMENT,
                mediaUrl = "https://storage.example/media/chat-1/m-legacy-remote.pdf",
                fileName = "invoice.pdf",
                mimeType = "application/pdf",
                stickerId = null,
                encryption = null,
            )
        } returns missingLegacyDownloadedFile

        // 4. Phase 2 E2EE video with media + thumbnail crypto keys (must NOT upload to legacy-media/)
        appDatabase.messageDao().insertOutbox(
            MessageEntity.fromDomain(
                Message(
                    id = "m-e2ee",
                    chatId = "chat-1",
                    senderId = "uid-bob",
                    content = "Encrypted caption",
                    type = MessageType.VIDEO,
                    mediaUrl = "https://storage.example/media/chat-1/m-e2ee.enc",
                    mediaThumbnailUrl = "https://storage.example/media/chat-1/m-e2ee_thumb.enc",
                    localUri = vanishedE2eeFile.absolutePath, // simulate path from old phone
                    localThumbUri = vanishedE2eeFile.absolutePath,
                    mediaKey = "mediaKeyB64",
                    mediaIv = "mediaIvB64",
                    mediaSha256 = "mediaShaB64",
                    thumbKey = "thumbKeyB64",
                    thumbIv = "thumbIvB64",
                    thumbSha256 = "thumbShaB64",
                    status = MessageStatus.READ,
                    timestamp = 4_000L,
                )
            )
        )
        // 5. Klipy GIF and Sticker (excluded from legacy-media backup)
        appDatabase.messageDao().insertOutbox(
            MessageEntity.fromDomain(
                Message(
                    id = "m-klipy",
                    chatId = "chat-1",
                    senderId = "uid-bob",
                    type = MessageType.GIF,
                    mediaUrl = "https://static.klipy.com/ii/sample.gif",
                    status = MessageStatus.READ,
                    timestamp = 4_100L,
                )
            )
        )
        appDatabase.messageDao().insertOutbox(
            MessageEntity.fromDomain(
                Message(
                    id = "m-sticker",
                    chatId = "chat-1",
                    senderId = "uid-bob",
                    type = MessageType.STICKER,
                    stickerId = "abc123",
                    mediaUrl = "https://storage.example/stickers/abc123.webp",
                    status = MessageStatus.READ,
                    timestamp = 4_200L,
                )
            )
        )

        // Run backup
        val backupResult = manager.runBackup()
        assertTrue("Expected backup success, got ${backupResult.exceptionOrNull()}", backupResult.isSuccess)
        val summary = backupResult.getOrThrow()
        assertEquals(nowMs, summary.createdAt)
        assertTrue(summary.legacyMediaComplete)
        assertEquals(BackupStage.COMPLETED, manager.backupProgress.value.stage)
        coVerify(exactly = 1) { messageRepository.syncAllChatMessages(listOf("chat-1")) }

        // Only the 2 legacy media rows should be uploaded to legacy-media/{keyGen}/
        val keyGen = backupKeyStore.load()!!.keyGeneration
        assertEquals(setOf("$keyGen/m-legacy-local", "$keyGen/m-legacy-remote"), backupSource.legacyBlobs["uid-alice"]?.keys)

        // Simulate wipe on a fresh phone: clear Room tables, clear BackupKeyStore, delete local legacy file
        appDatabase.clearAllTables()
        backupKeyStore.clear()
        legacyLocalFile.delete()
        assertEquals(0, appDatabase.messageDao().getMessageCount())

        // Restore with passphrase
        val restoreResult = manager.restoreBackup("my-strong-backup-passphrase")
        assertTrue("Expected restore success, got ${restoreResult.exceptionOrNull()}", restoreResult.isSuccess)
        assertEquals(RestoreStage.COMPLETED, manager.restoreProgress.value.stage)

        // Reopen a fresh Room instance (simulating app restart after restore)
        appDatabase = openRoomDb(dbName)

        val restoredChat = appDatabase.chatDao().getChatById("chat-1")
        assertNotNull(restoredChat)
        assertTrue(restoredChat!!.isPinned)
        assertEquals("Bob", restoredChat.name)

        val restoredSync = appDatabase.messageSyncStateDao().getState("chat-1")
        assertEquals(MessageSyncStateEntity("chat-1", 2, 4_000L), restoredSync)

        val restoredText = appDatabase.messageDao().getMessageById("m-text")!!
        assertEquals("Hello from decrypted history!", restoredText.content)
        assertTrue(restoredText.isStarred)

        val restoredE2ee = appDatabase.messageDao().getMessageById("m-e2ee")!!
        assertEquals("mediaKeyB64", restoredE2ee.mediaKey)
        assertEquals("mediaIvB64", restoredE2ee.mediaIv)
        assertEquals("mediaShaB64", restoredE2ee.mediaSha256)
        assertEquals("thumbKeyB64", restoredE2ee.thumbKey)
        assertEquals("thumbIvB64", restoredE2ee.thumbIv)
        assertEquals("thumbShaB64", restoredE2ee.thumbSha256)
        // Missing files on the new device must have been scrubbed to NULL on restore
        assertNull(restoredE2ee.localUri)
        assertNull(restoredE2ee.localThumbUri)

        // Legacy photo was deleted above, so its localUri was scrubbed too
        val restoredLegacy = appDatabase.messageDao().getMessageById("m-legacy-local")!!
        assertNull(restoredLegacy.localUri)

        // Verify persisted backup key works for lazy legacy-media fallback decryption!
        assertNotNull(backupKeyStore.load())
        val fetcher = LegacyMediaBackupFetcher(context, backupSource, backupKeyStore, authSource)
        val decryptedLegacyOut = File(context.cacheDir, "decrypted_legacy_${UUID.randomUUID()}.jpg").also { tempFiles += it }
        assertTrue(fetcher.downloadAndDecrypt("m-legacy-local", decryptedLegacyOut))
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte()),
            decryptedLegacyOut.readBytes(),
        )
    }

    @Test
    fun `restore rejects wrong passphrase fast without downloading db blob`() = runTest {
        val manager = newManager()
        manager.configurePassphrase("correct-passphrase-999")
        assertTrue(manager.runBackup().isSuccess)
        backupKeyStore.clear()

        val result = manager.restoreBackup("wrong-passphrase-999")
        assertTrue(result.exceptionOrNull() is WrongPassphraseException)
        assertEquals(RestoreStage.FAILED, manager.restoreProgress.value.stage)
        assertEquals(0, backupSource.downloadDbCalls)
    }

    @Test
    fun `interrupted upload leaves prior good manifest and db blob intact`() = runTest {
        val manager = newManager()
        manager.configurePassphrase("atomic-commit-passphrase")

        // First backup succeeds at t1
        assertTrue(manager.runBackup().isSuccess)
        val firstManifest = backupSource.manifests["uid-alice"]!!
        val firstDbName = BackupCrypto.parseManifest(firstManifest, minIterations = 1_000).dbObjectName
        assertTrue(backupSource.dbBlobs["uid-alice"]!!.containsKey(firstDbName))

        // Second backup fails during uploadManifest at t2: orphan new db blob must be cleaned up!
        nowMs += 60_000L
        backupSource.failNextManifestUpload = true
        val second = manager.runBackup()
        assertTrue(second.isFailure)
        assertEquals(BackupStage.FAILED, manager.backupProgress.value.stage)

        // Prior manifest and ONLY prior DB blob remain completely intact!
        assertEquals(firstManifest, backupSource.manifests["uid-alice"])
        assertEquals(setOf(firstDbName), backupSource.dbBlobs["uid-alice"]!!.keys)
    }

    @Test
    fun `missing legacy media sets legacyMediaComplete false while still committing DB backup`() = runTest {
        val manager = newManager()
        manager.configurePassphrase("partial-legacy-passphrase")

        appDatabase.messageDao().insertOutbox(
            MessageEntity.fromDomain(
                Message(
                    id = "m-missing-legacy",
                    chatId = "chat-1",
                    senderId = "uid-bob",
                    type = MessageType.IMAGE,
                    mediaUrl = "https://storage.example/media/chat-1/m-missing-legacy.jpg",
                    localUri = null,
                    status = MessageStatus.READ,
                    timestamp = 1_000L,
                )
            )
        )
        coEvery {
            mediaFileManager.downloadFor(any(), eq("m-missing-legacy"), any(), any(), any(), any(), any(), any())
        } throws IOException("404 Gone")

        val result = manager.runBackup()
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().legacyMediaComplete)
        val parsed = BackupCrypto.parseManifest(backupSource.manifests["uid-alice"]!!, minIterations = 1_000)
        assertFalse(parsed.legacyMediaComplete)
    }

    @Test
    fun `transient legacy media upload failure succeeds on retry and keeps legacyMediaComplete true`() = runTest {
        val manager = newManager()
        manager.configurePassphrase("retry-legacy-passphrase-123")

        val legacyFile = File(context.cacheDir, "retry_${UUID.randomUUID()}.jpg").apply {
            writeBytes(byteArrayOf(7, 8, 9, 10))
            tempFiles += this
        }
        appDatabase.messageDao().insertOutbox(
            MessageEntity.fromDomain(
                Message(
                    id = "m-retry-1",
                    chatId = "chat-1",
                    senderId = "uid-alice",
                    type = MessageType.IMAGE,
                    mediaUrl = "https://storage.example/media/chat-1/m-retry-1.jpg",
                    localUri = legacyFile.absolutePath,
                    status = MessageStatus.SENT,
                    timestamp = 1_000L,
                )
            )
        )

        // Fail first 2 attempts (e.g. brief Wi-Fi drop), succeed on attempt 3
        backupSource.failLegacyUploadsRemaining = 2
        val result = manager.runBackup()
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().legacyMediaComplete)
        assertEquals(3, backupSource.legacyUploadCount)
        val keyGen = backupKeyStore.load()!!.keyGeneration
        assertTrue(backupSource.legacyBlobs["uid-alice"]!!.containsKey("$keyGen/m-retry-1"))
    }

    @Test
    fun `persistent legacy media upload failure exhausts retries and sets legacyMediaComplete false`() = runTest {
        val manager = newManager()
        manager.configurePassphrase("retry-exhaust-passphrase-123")

        val legacyFile = File(context.cacheDir, "retry_fail_${UUID.randomUUID()}.jpg").apply {
            writeBytes(byteArrayOf(11, 12, 13, 14))
            tempFiles += this
        }
        appDatabase.messageDao().insertOutbox(
            MessageEntity.fromDomain(
                Message(
                    id = "m-retry-fail",
                    chatId = "chat-1",
                    senderId = "uid-alice",
                    type = MessageType.IMAGE,
                    mediaUrl = "https://storage.example/media/chat-1/m-retry-fail.jpg",
                    localUri = legacyFile.absolutePath,
                    status = MessageStatus.SENT,
                    timestamp = 1_000L,
                )
            )
        )

        backupSource.failLegacyUploadsRemaining = 10
        val result = manager.runBackup()
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().legacyMediaComplete)
        assertEquals(3, backupSource.legacyUploadCount)
    }

    @Test
    fun `passphrase rotation is atomic and re-keys remote-only legacy media after local deletion`() = runTest {
        val manager = newManager()
        assertTrue(manager.configurePassphrase("original-passphrase-111").isSuccess)

        val payload = byteArrayOf(10, 20, 30, 40, 50)
        val legacyFile = File(context.cacheDir, "rot_${UUID.randomUUID()}.jpg").apply {
            writeBytes(payload)
            tempFiles += this
        }
        appDatabase.messageDao().insertOutbox(
            MessageEntity.fromDomain(
                Message(
                    id = "m-rot-1",
                    chatId = "chat-1",
                    senderId = "uid-alice",
                    type = MessageType.IMAGE,
                    mediaUrl = "https://storage.example/media/chat-1/m-rot-1.jpg",
                    localUri = legacyFile.absolutePath,
                    status = MessageStatus.SENT,
                    timestamp = 1_000L,
                )
            )
        )
        assertTrue(manager.runBackup().isSuccess)
        val oldKey = backupKeyStore.load()!!
        val oldKeyGen = oldKey.keyGeneration
        val oldManifest = backupSource.manifests["uid-alice"]!!

        // Simulate post-Step-9 restored phone: local file gone AND legacy Storage URL returns 404.
        legacyFile.delete()
        appDatabase.messageDao().updateLocalUri("m-rot-1", null)
        coEvery {
            mediaFileManager.downloadFor(any(), eq("m-rot-1"), any(), any(), any(), any(), any(), any())
        } throws IOException("404 scrubbed")

        // Stage passphrase rotation; active key must remain oldKey until rotation commits!
        assertTrue(manager.configurePassphrase("rotated-passphrase-222").isSuccess)
        assertEquals(oldKey, backupKeyStore.load())

        // Failed rotation leaves prior manifest, old legacy blobs, and old active key untouched
        nowMs += 60_000L
        backupSource.failNextManifestUpload = true
        assertTrue(manager.runBackup().isFailure)
        assertEquals(oldManifest, backupSource.manifests["uid-alice"])
        assertEquals(oldKey, backupKeyStore.load())
        assertTrue(backupSource.legacyBlobs["uid-alice"]!!.containsKey("$oldKeyGen/m-rot-1"))

        // Successful rotation re-keys m-rot-1 from oldKeyGen to newKeyGen, promotes new key, deletes oldKeyGen
        nowMs += 60_000L
        val rotated = manager.runBackup()
        assertTrue("Expected rotation success, got ${rotated.exceptionOrNull()}", rotated.isSuccess)
        assertTrue(rotated.getOrThrow().legacyMediaComplete)
        val newKey = backupKeyStore.load()!!
        assertFalse(newKey.keyGeneration == oldKeyGen)
        assertFalse(backupSource.legacyBlobs["uid-alice"]!!.containsKey("$oldKeyGen/m-rot-1"))
        assertTrue(backupSource.legacyBlobs["uid-alice"]!!.containsKey("${newKey.keyGeneration}/m-rot-1"))

        // Verify lazy fetcher decrypts the re-keyed blob with the new active key!
        val fetcher = LegacyMediaBackupFetcher(context, backupSource, backupKeyStore, authSource)
        val out = File(context.cacheDir, "rot_dec_${UUID.randomUUID()}.jpg").also { tempFiles += it }
        assertTrue(fetcher.downloadAndDecrypt("m-rot-1", out))
        assertArrayEquals(payload, out.readBytes())
    }

    @Test
    fun `second backup skips already uploaded legacy media and deletes superseded db blob`() = runTest {
        val manager = newManager()
        manager.configurePassphrase("incremental-passphrase-123")

        val legacyFile = File(context.cacheDir, "inc_${UUID.randomUUID()}.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
            tempFiles += this
        }
        appDatabase.messageDao().insertOutbox(
            MessageEntity.fromDomain(
                Message(
                    id = "m-inc-1",
                    chatId = "chat-1",
                    senderId = "uid-alice",
                    type = MessageType.IMAGE,
                    mediaUrl = "https://storage.example/media/chat-1/m-inc-1.jpg",
                    localUri = legacyFile.absolutePath,
                    status = MessageStatus.SENT,
                    timestamp = 1_000L,
                )
            )
        )

        assertTrue(manager.runBackup().isSuccess)
        val firstDbName = BackupCrypto.parseManifest(backupSource.manifests["uid-alice"]!!, minIterations = 1_000).dbObjectName
        assertEquals(1, backupSource.legacyUploadCount)

        // Second backup with same passphrase: legacy media skipped, old db blob deleted
        nowMs += 60_000L
        assertTrue(manager.runBackup().isSuccess)
        val secondDbName = BackupCrypto.parseManifest(backupSource.manifests["uid-alice"]!!, minIterations = 1_000).dbObjectName
        assertEquals(1, backupSource.legacyUploadCount)
        assertFalse(backupSource.dbBlobs["uid-alice"]!!.containsKey(firstDbName))
        assertTrue(backupSource.dbBlobs["uid-alice"]!!.containsKey(secondDbName))

        // Third backup after passphrase rotation: legacy media MUST be re-uploaded with the new key
        nowMs += 60_000L
        assertTrue(manager.configurePassphrase("brand-new-passphrase-456").isSuccess)
        assertTrue(manager.runBackup().isSuccess)
        assertEquals(2, backupSource.legacyUploadCount)
    }

    @Test
    fun `restore rejects backup from newer schema version before downloading db`() = runTest {
        val manager = newManager()
        val salt = BackupCrypto.randomSalt()
        val master = BackupCrypto.deriveMasterKey("future-schema-passphrase", salt, iterations = 1_000)
        val futureManifest = BackupCrypto.createManifest(
            masterKey = master,
            salt = salt,
            kdfIterations = 1_000,
            createdAt = nowMs,
            dbObjectName = "db_$nowMs.enc",
            dbCipherSizeBytes = 1024L,
            legacyMediaComplete = true,
            inner = BackupManifestInner(
                dbIvB64 = "AAAAAAAAAAAAAAAA",
                dbSha256B64 = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
                schemaVersion = AppDatabase.CURRENT_VERSION + 5,
                messageCount = 10,
                legacyMediaIds = emptyList(),
            ),
        )
        backupSource.manifests["uid-alice"] = BackupCrypto.serializeManifest(futureManifest)

        val result = manager.restoreBackup("future-schema-passphrase")
        assertTrue(result.exceptionOrNull() is UnsupportedBackupVersionException)
        assertEquals(0, backupSource.downloadDbCalls)
    }

    @Test
    fun `localStatus flow reflects configuration and last backup metadata`() = runTest {
        val manager = newManager()
        val initial = manager.localStatus.first()
        assertFalse(initial.isPassphraseConfigured)
        assertNull(initial.lastBackupAt)

        manager.configurePassphrase("status-check-passphrase")
        assertTrue(manager.runBackup().isSuccess)

        val after = manager.localStatus.first()
        assertTrue(after.isPassphraseConfigured)
        assertEquals(nowMs, after.lastBackupAt)
        assertTrue(after.lastBackupLegacyMediaComplete)
        assertTrue((after.lastBackupSizeBytes ?: 0L) > 0L)

        manager.setBackupFrequency(BackupFrequency.DAILY)
        io.mockk.verify(exactly = 1) { backupScheduler.schedule(BackupFrequency.DAILY) }

        manager.clearForSignOut()
        io.mockk.verify(exactly = 1) { backupScheduler.cancel() }
        assertEquals(BackupStage.IDLE, manager.backupProgress.value.stage)
    }

    private class InMemoryKeyWrapper : BackupKeyWrapper {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        private val rng = SecureRandom()

        override fun wrap(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
            val iv = ByteArray(12).also(rng::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(plaintext) to iv
        }

        override fun unwrap(ciphertext: ByteArray, iv: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(ciphertext)
        }
    }

    private class InMemoryBackupSource : BackupSource {
        override val isSupported: Boolean = true
        val manifests = mutableMapOf<String, String>()
        val dbBlobs = mutableMapOf<String, MutableMap<String, ByteArray>>()
        val legacyBlobs = mutableMapOf<String, MutableMap<String, ByteArray>>()
        var downloadDbCalls = 0
        var legacyUploadCount = 0
        var failNextDbUpload = false
        var failNextManifestUpload = false
        var failLegacyUploadsRemaining = 0

        override suspend fun fetchManifest(uid: String): String? = manifests[uid]

        override suspend fun uploadManifest(uid: String, json: String) {
            if (failNextManifestUpload) {
                failNextManifestUpload = false
                throw IOException("Simulated failure committing manifest.json")
            }
            manifests[uid] = json
        }

        override suspend fun uploadDbBlob(
            uid: String,
            objectName: String,
            file: File,
            onProgress: ((Float) -> Unit)?,
        ) {
            if (failNextDbUpload) {
                failNextDbUpload = false
                throw IOException("Simulated network drop during DB upload")
            }
            onProgress?.invoke(0.5f)
            dbBlobs.getOrPut(uid) { mutableMapOf() }[objectName] = file.readBytes()
            onProgress?.invoke(1f)
        }

        override suspend fun downloadDbBlob(
            uid: String,
            objectName: String,
            destFile: File,
            onProgress: ((Float) -> Unit)?,
        ) {
            downloadDbCalls++
            val bytes = dbBlobs[uid]?.get(objectName) ?: throw IOException("Not found: $objectName")
            destFile.writeBytes(bytes)
            onProgress?.invoke(1f)
        }

        override suspend fun deleteDbBlob(uid: String, objectName: String) {
            dbBlobs[uid]?.remove(objectName)
        }

        override suspend fun uploadLegacyMediaBlob(uid: String, keyGen: String, messageId: String, file: File) {
            legacyUploadCount++
            if (failLegacyUploadsRemaining > 0) {
                failLegacyUploadsRemaining--
                throw IOException("The server has terminated the upload session")
            }
            legacyBlobs.getOrPut(uid) { mutableMapOf() }["$keyGen/$messageId"] = file.readBytes()
        }

        override suspend fun downloadLegacyMediaBlob(
            uid: String,
            keyGen: String,
            messageId: String,
            destFile: File,
        ): Boolean {
            val bytes = legacyBlobs[uid]?.get("$keyGen/$messageId") ?: return false
            destFile.writeBytes(bytes)
            return true
        }

        override suspend fun deleteLegacyMediaBlob(uid: String, keyGen: String, messageId: String) {
            legacyBlobs[uid]?.remove("$keyGen/$messageId")
        }
    }
}
