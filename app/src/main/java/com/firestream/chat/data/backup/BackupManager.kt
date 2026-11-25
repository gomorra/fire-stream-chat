// region: AGENT-NOTE
// Responsibility: Orchestrates passphrase-protected encrypted cloud backups and
//   restores of AppDatabase (`fire_stream_chat.db`) plus legacy plaintext media
//   blobs to/from `backups/{uid}/` in Firebase Storage. Never backs up
//   SignalDatabase (`signal.db`).
// Owns: Atomic backup commit protocol (upload collision-free `db_<ts>_<hex>.enc`
//   and key-generation-namespaced `legacy-media/{keyGen}/{id}.enc`, commit
//   `manifest.json` last, then delete superseded blobs), atomic passphrase
//   rotation, SQLite WAL checkpoint + `VACUUM INTO` snapshotting, integrity &
//   schema verification on restore, and unreadable/missing local-path scrubbing
//   before DB swap.
// Collaborators: BackupCrypto, BackupKeyStore, BackupSource, BackupScheduler,
//   AppDatabase, MessageDao, ChatDao, MessageRepository, MediaFileManager,
//   PreferencesDataStore, AuthSource.
// Don't put here: UI state or dialogs (BackupViewModel / RestoreBackupViewModel),
//   WorkManager scheduling (BackupWorker), or Signal key backup (never backed up).
// endregion

package com.firestream.chat.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.firestream.chat.data.crypto.MediaCrypto
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.data.util.parseMessageType
import com.firestream.chat.data.util.rethrowIfCancellation
import com.firestream.chat.data.worker.BackupScheduler
import com.firestream.chat.domain.model.BackupFrequency
import com.firestream.chat.domain.model.BackupProgress
import com.firestream.chat.domain.model.BackupStage
import com.firestream.chat.domain.model.LocalBackupStatus
import com.firestream.chat.domain.model.RemoteBackupSummary
import com.firestream.chat.domain.model.RestoreProgress
import com.firestream.chat.domain.model.RestoreStage
import com.firestream.chat.domain.model.UnsupportedBackupVersionException
import com.firestream.chat.domain.model.WrongPassphraseException
import com.firestream.chat.domain.repository.BackupRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.util.KlipyUrls
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager internal constructor(
    private val context: Context,
    private val appDatabase: AppDatabase,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val messageRepository: MessageRepository,
    private val mediaFileManager: MediaFileManager,
    private val backupSource: BackupSource,
    private val backupKeyStore: BackupKeyStore,
    private val preferencesDataStore: PreferencesDataStore,
    private val authSource: AuthSource,
    private val backupScheduler: BackupScheduler,
    private val kdfIterations: Int,
    private val minManifestIterations: Int,
    private val databaseName: String,
    private val clock: () -> Long,
) : BackupRepository {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        appDatabase: AppDatabase,
        messageDao: MessageDao,
        chatDao: ChatDao,
        messageRepository: MessageRepository,
        mediaFileManager: MediaFileManager,
        backupSource: BackupSource,
        backupKeyStore: BackupKeyStore,
        preferencesDataStore: PreferencesDataStore,
        authSource: AuthSource,
        backupScheduler: BackupScheduler,
    ) : this(
        context = context,
        appDatabase = appDatabase,
        messageDao = messageDao,
        chatDao = chatDao,
        messageRepository = messageRepository,
        mediaFileManager = mediaFileManager,
        backupSource = backupSource,
        backupKeyStore = backupKeyStore,
        preferencesDataStore = preferencesDataStore,
        authSource = authSource,
        backupScheduler = backupScheduler,
        kdfIterations = BackupCrypto.DEFAULT_KDF_ITERATIONS,
        minManifestIterations = BackupCrypto.MIN_KDF_ITERATIONS,
        databaseName = DATABASE_NAME,
        clock = System::currentTimeMillis,
    )

    private val operationMutex = Mutex()

    private val _backupProgress = MutableStateFlow(BackupProgress())
    override val backupProgress: StateFlow<BackupProgress> = _backupProgress.asStateFlow()

    private val _restoreProgress = MutableStateFlow(RestoreProgress())
    override val restoreProgress: StateFlow<RestoreProgress> = _restoreProgress.asStateFlow()

    override val isSupported: Boolean get() = backupSource.isSupported

    override val localStatus: Flow<LocalBackupStatus> = combine(
        backupKeyStore.isConfiguredFlow,
        preferencesDataStore.lastBackupAtFlow,
        preferencesDataStore.lastBackupLegacyMediaCompleteFlow,
        preferencesDataStore.lastBackupSizeBytesFlow,
        preferencesDataStore.backupFrequencyFlow,
    ) { configured, lastAt, legacyComplete, sizeBytes, frequency ->
        LocalBackupStatus(
            isSupported = backupSource.isSupported,
            isPassphraseConfigured = configured,
            lastBackupAt = lastAt,
            lastBackupLegacyMediaComplete = legacyComplete,
            lastBackupSizeBytes = sizeBytes,
            frequency = frequency,
        )
    }

    override suspend fun configurePassphrase(passphrase: String): Result<Unit> = operationMutex.withLock {
        runCatching {
            require(passphrase.length >= BackupCrypto.MIN_PASSPHRASE_LENGTH) {
                "Passphrase must be at least ${BackupCrypto.MIN_PASSPHRASE_LENGTH} characters"
            }
            val salt = BackupCrypto.randomSalt()
            val masterKey = withContext(Dispatchers.Default) {
                BackupCrypto.deriveMasterKey(passphrase, salt, kdfIterations)
            }
            val newKey = StoredBackupKey(
                masterKey = masterKey,
                salt = salt,
                kdfIterations = kdfIterations,
            )
            // If an active key already exists, stage the new key for atomic rotation
            // so lazy decryption of existing remote legacy-media blobs keeps working
            // until the next backup commits under the new key generation.
            if (backupKeyStore.load() != null) {
                backupKeyStore.savePendingRotation(newKey)
            } else {
                backupKeyStore.save(newKey)
            }
        }.onFailure { it.rethrowIfCancellation() }
    }

    override suspend fun hasConfiguredPassphrase(): Boolean =
        backupKeyStore.load() != null || backupKeyStore.loadPendingRotation() != null

    override suspend fun hasLocalMessages(): Boolean = withContext(Dispatchers.IO) {
        messageDao.getMessageCount() > 0
    }

    override suspend fun isRestorePromptResolved(): Boolean =
        preferencesDataStore.isRestorePromptResolved()

    override suspend fun markRestorePromptResolved() {
        withContext(NonCancellable) {
            preferencesDataStore.setRestorePromptResolved(true)
        }
    }

    override suspend fun setBackupFrequency(frequency: BackupFrequency) {
        withContext(NonCancellable) {
            preferencesDataStore.setBackupFrequency(frequency)
            backupScheduler.schedule(frequency)
        }
    }

    override fun resetProgress() {
        _backupProgress.value = BackupProgress()
        _restoreProgress.value = RestoreProgress()
    }

    /**
     * Fences sign-out against any in-flight backup/restore, cancels scheduled
     * periodic backups, clears stored keys and backup metadata, resets progress
     * flows, and sweeps any leftover temp snapshots from `cacheDir`.
     */
    suspend fun clearForSignOut() = operationMutex.withLock {
        backupScheduler.cancel()
        backupKeyStore.clear()
        preferencesDataStore.clearBackupPreferences()
        resetProgress()
        withContext(Dispatchers.IO) {
            sweepStaleTempFiles()
        }
    }

    override suspend fun fetchRemoteBackupSummary(): Result<RemoteBackupSummary?> = runCatching {
        if (!backupSource.isSupported) return@runCatching null
        val uid = authSource.currentUserId ?: return@runCatching null
        val rawJson = backupSource.fetchManifest(uid) ?: return@runCatching null
        val manifest = BackupCrypto.parseManifest(rawJson, minIterations = minManifestIterations)
        RemoteBackupSummary(
            createdAt = manifest.createdAt,
            dbCipherSizeBytes = manifest.dbCipherSizeBytes,
            legacyMediaComplete = manifest.legacyMediaComplete,
        )
    }.onFailure { it.rethrowIfCancellation() }

    override suspend fun runBackup(): Result<RemoteBackupSummary> = operationMutex.withLock {
        val runTag = "${clock()}_${BackupCrypto.randomHexSuffix()}"
        val snapshotFile = File(context.cacheDir, "backup_snapshot_$runTag.db")
        val gzipFile = File(context.cacheDir, "backup_snapshot_$runTag.db.gz")
        val encDbFile = File(context.cacheDir, "backup_snapshot_$runTag.db.enc")
        var uid: String? = null
        var uploadedDbObjectName: String? = null
        var manifestCommitted = false
        try {
            check(backupSource.isSupported) { "Encrypted backups are not supported on this backend" }
            uid = authSource.currentUserId ?: error("Not signed in")
            val activeKey = backupKeyStore.load()
            val pendingKey = backupKeyStore.loadPendingRotation()
            val targetKey = pendingKey ?: activeKey
                ?: error("Backup passphrase is not configured")
            val isRotating = pendingKey != null && activeKey != null && pendingKey.saltB64 != activeKey.saltB64

            // Stage 1: Sync all chats so the DB snapshot is up to date.
            _backupProgress.value = BackupProgress(stage = BackupStage.SYNCING_CHATS, fraction = 0.05f)
            val chatIds = withContext(Dispatchers.IO) { chatDao.getAllChatIds() }
            if (chatIds.isNotEmpty()) {
                messageRepository.syncAllChatMessages(chatIds)
            }

            // Load previous manifest (if any). Transient Storage/network errors propagate
            // and abort cleanly rather than silently wiping legacyMediaIds or leaking db_*.enc.
            val previousManifest = backupSource.fetchManifest(uid)?.let { raw ->
                BackupCrypto.parseManifest(raw, minIterations = minManifestIterations)
            }
            val previousInnerForTarget = if (previousManifest != null && previousManifest.saltB64 == targetKey.saltB64) {
                runCatching { BackupCrypto.decryptManifestInner(previousManifest, targetKey.masterKey) }.getOrNull()
            } else {
                null
            }
            val previousInnerForActive = if (isRotating && previousManifest != null && previousManifest.saltB64 == activeKey!!.saltB64) {
                runCatching { BackupCrypto.decryptManifestInner(previousManifest, activeKey.masterKey) }.getOrNull()
            } else {
                null
            }
            val alreadyBackedUpLegacyIds = previousInnerForTarget?.legacyMediaIds?.toSet().orEmpty()
            val prevGenLegacyIds = previousInnerForActive?.legacyMediaIds?.toSet().orEmpty()

            // Stage 2: Download any missing/unreadable legacy plaintext media locally so we can back it up.
            val legacyMessages = withContext(Dispatchers.IO) {
                messageDao.getLegacyMediaMessages()
                    .filter { msg -> msg.mediaUrl != null && !KlipyUrls.isMedia(msg.mediaUrl) }
            }
            val needDownload = legacyMessages.filter { msg ->
                msg.id !in alreadyBackedUpLegacyIds && !isReadableFile(msg.localUri)
            }
            _backupProgress.value = BackupProgress(
                stage = BackupStage.DOWNLOADING_LEGACY_MEDIA,
                completedItems = 0,
                totalItems = needDownload.size,
                fraction = 0.10f,
            )
            needDownload.forEachIndexed { idx, msg ->
                try {
                    val downloaded = mediaFileManager.downloadFor(
                        chatId = msg.chatId,
                        messageId = msg.id,
                        type = parseMessageType(msg.type),
                        mediaUrl = msg.mediaUrl!!,
                        fileName = msg.fileName,
                        mimeType = msg.mimeType,
                        stickerId = msg.stickerId,
                        encryption = null,
                    )
                    if (downloaded != null) {
                        messageDao.updateLocalUri(msg.id, downloaded.absolutePath)
                    }
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    Log.w(TAG, "Legacy media download failed for msg=${msg.id}", e)
                }
                val done = idx + 1
                val sub = if (needDownload.isEmpty()) 1f else done.toFloat() / needDownload.size
                _backupProgress.value = BackupProgress(
                    stage = BackupStage.DOWNLOADING_LEGACY_MEDIA,
                    completedItems = done,
                    totalItems = needDownload.size,
                    fraction = 0.10f + 0.20f * sub,
                )
            }

            // Stage 3: Clean SQLite snapshot via WAL checkpoint + VACUUM INTO (never copies live WAL; never touches signal.db).
            _backupProgress.value = BackupProgress(stage = BackupStage.SNAPSHOTTING_DB, fraction = 0.32f)
            val messageCount = withContext(Dispatchers.IO) {
                snapshotFile.delete()
                val db = appDatabase.openHelper.writableDatabase
                db.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
                val escaped = snapshotFile.absolutePath.replace("'", "''")
                db.execSQL("VACUUM INTO '$escaped'")
                messageDao.getMessageCount()
            }

            // Stage 4: Gzip-compress and encrypt snapshot with dbKey via MediaCrypto.
            _backupProgress.value = BackupProgress(stage = BackupStage.ENCRYPTING_DB, fraction = 0.40f)
            val dbCipherInfo = withContext(Dispatchers.IO) {
                gzipFile(snapshotFile, gzipFile)
                snapshotFile.delete()
                val dbKey = BackupCrypto.deriveDbKey(targetKey.masterKey)
                val info = MediaCrypto.encryptFile(
                    input = gzipFile,
                    output = encDbFile,
                    keyBytes = dbKey,
                    ivBytes = MediaCrypto.randomNonce(),
                )
                gzipFile.delete()
                info
            }

            // Stage 5: Upload collision-free versioned db_<timestamp>_<hex>.enc.
            val nowMs = clock()
            val dbObjectName = "db_${nowMs}_${BackupCrypto.randomHexSuffix()}.enc"
            _backupProgress.value = BackupProgress(stage = BackupStage.UPLOADING_DB, fraction = 0.48f)
            backupSource.uploadDbBlob(uid, dbObjectName, encDbFile) { p ->
                _backupProgress.value = BackupProgress(
                    stage = BackupStage.UPLOADING_DB,
                    fraction = 0.48f + 0.22f * p.coerceIn(0f, 1f),
                )
            }
            uploadedDbObjectName = dbObjectName
            encDbFile.delete()

            // Stage 6: Incrementally encrypt & upload legacy media blobs to key-generation namespace.
            val refreshedLegacy = withContext(Dispatchers.IO) {
                messageDao.getLegacyMediaMessages()
                    .filter { msg -> msg.mediaUrl != null && !KlipyUrls.isMedia(msg.mediaUrl) }
            }
            val uploadedLegacyIds = alreadyBackedUpLegacyIds.toMutableSet()
            val toUpload = refreshedLegacy.filter { it.id !in uploadedLegacyIds }
            val targetKeyGen = targetKey.keyGeneration
            var allLegacyComplete = true
            _backupProgress.value = BackupProgress(
                stage = BackupStage.UPLOADING_LEGACY_MEDIA,
                completedItems = 0,
                totalItems = toUpload.size,
                fraction = 0.70f,
            )
            toUpload.forEachIndexed { idx, msg ->
                val readableLocal = msg.localUri?.takeIf { isReadableFile(it) }?.let(::File)
                var rekeyPlainTemp: File? = null
                val sourceFile = when {
                    readableLocal != null -> readableLocal
                    isRotating && msg.id in prevGenLegacyIds -> {
                        val oldKey = activeKey!!
                        // Post-Step-9 rotation: fetch from prior key generation and decrypt locally.
                        val oldEnc = File.createTempFile("rekey_old_${msg.id}_", ".enc", context.cacheDir)
                        val oldPlain = File.createTempFile("rekey_plain_${msg.id}_", ".bin", context.cacheDir)
                        try {
                            val fetched = backupSource.downloadLegacyMediaBlob(uid, oldKey.keyGeneration, msg.id, oldEnc)
                            if (fetched) {
                                withContext(Dispatchers.IO) {
                                    BackupCrypto.decryptLegacyMediaFile(oldEnc, oldPlain, oldKey.masterKey, msg.id)
                                }
                                rekeyPlainTemp = oldPlain
                                oldPlain
                            } else {
                                oldPlain.delete()
                                null
                            }
                        } catch (e: Exception) {
                            e.rethrowIfCancellation()
                            Log.w(TAG, "Failed to re-key legacy media msg=${msg.id} from prior generation", e)
                            oldPlain.delete()
                            null
                        } finally {
                            oldEnc.delete()
                        }
                    }
                    else -> null
                }

                if (sourceFile == null) {
                    allLegacyComplete = false
                } else {
                    val tempEnc = File.createTempFile("legacy_enc_${msg.id}_", ".enc", context.cacheDir)
                    try {
                        withContext(Dispatchers.IO) {
                            BackupCrypto.encryptLegacyMediaFile(sourceFile, tempEnc, targetKey.masterKey, msg.id)
                        }
                        backupSource.uploadLegacyMediaBlob(uid, targetKeyGen, msg.id, tempEnc)
                        uploadedLegacyIds += msg.id
                    } catch (e: Exception) {
                        e.rethrowIfCancellation()
                        Log.w(TAG, "Legacy media upload failed for msg=${msg.id}", e)
                        allLegacyComplete = false
                    } finally {
                        tempEnc.delete()
                        rekeyPlainTemp?.delete()
                    }
                }
                val done = idx + 1
                val sub = if (toUpload.isEmpty()) 1f else done.toFloat() / toUpload.size
                _backupProgress.value = BackupProgress(
                    stage = BackupStage.UPLOADING_LEGACY_MEDIA,
                    completedItems = done,
                    totalItems = toUpload.size,
                    fraction = 0.70f + 0.22f * sub,
                )
            }
            if (refreshedLegacy.any { it.id !in uploadedLegacyIds }) {
                allLegacyComplete = false
            }

            // Stage 7: Commit manifest.json LAST, promote rotated key, then delete superseded blobs.
            _backupProgress.value = BackupProgress(stage = BackupStage.FINALIZING, fraction = 0.94f)
            val inner = BackupManifestInner(
                dbIvB64 = dbCipherInfo.ivB64,
                dbSha256B64 = dbCipherInfo.sha256B64,
                schemaVersion = AppDatabase.CURRENT_VERSION,
                messageCount = messageCount,
                legacyMediaIds = uploadedLegacyIds.sorted(),
            )
            val manifest = BackupCrypto.createManifest(
                masterKey = targetKey.masterKey,
                salt = targetKey.salt,
                kdfIterations = targetKey.kdfIterations,
                createdAt = nowMs,
                dbObjectName = dbObjectName,
                dbCipherSizeBytes = dbCipherInfo.ciphertextSize,
                legacyMediaComplete = allLegacyComplete,
                inner = inner,
            )
            backupSource.uploadManifest(uid, BackupCrypto.serializeManifest(manifest))
            manifestCommitted = true

            // Post-commit state promotion & cleanup must finish even if the caller's
            // scope cancels right after manifest.json lands remotely.
            withContext(NonCancellable) {
                if (isRotating) {
                    backupKeyStore.save(targetKey)
                    val oldKeyGen = activeKey!!.keyGeneration
                    for (oldId in prevGenLegacyIds) {
                        try {
                            backupSource.deleteLegacyMediaBlob(uid, oldKeyGen, oldId)
                        } catch (e: Exception) {
                            e.rethrowIfCancellation()
                            Log.w(TAG, "Failed to clean up old-generation legacy blob $oldId", e)
                        }
                    }
                }

                val prevDbName = previousManifest?.dbObjectName
                if (prevDbName != null && prevDbName != dbObjectName) {
                    try {
                        backupSource.deleteDbBlob(uid, prevDbName)
                    } catch (e: Exception) {
                        e.rethrowIfCancellation()
                        Log.w(TAG, "Failed to delete superseded backup blob $prevDbName", e)
                    }
                }

                preferencesDataStore.recordBackupSuccess(
                    timestampMs = nowMs,
                    sizeBytes = dbCipherInfo.ciphertextSize,
                    legacyMediaComplete = allLegacyComplete,
                )
            }

            val summary = RemoteBackupSummary(
                createdAt = nowMs,
                dbCipherSizeBytes = dbCipherInfo.ciphertextSize,
                legacyMediaComplete = allLegacyComplete,
            )
            _backupProgress.value = BackupProgress(stage = BackupStage.COMPLETED, fraction = 1f)
            Result.success(summary)
        } catch (e: CancellationException) {
            // Never leave singleton _backupProgress wedged on an active stage when
            // WorkManager stops BackupWorker or a caller cancels.
            withContext(NonCancellable) {
                val orphanUid = uid
                val orphanDb = uploadedDbObjectName
                if (!manifestCommitted && orphanUid != null && orphanDb != null) {
                    runCatching { backupSource.deleteDbBlob(orphanUid, orphanDb) }
                }
                _backupProgress.value = BackupProgress()
            }
            throw e
        } catch (e: Exception) {
            // Best-effort cleanup of newly uploaded orphan DB blob if manifest never committed.
            val orphanUid = uid
            val orphanDb = uploadedDbObjectName
            if (!manifestCommitted && orphanUid != null && orphanDb != null) {
                withContext(NonCancellable) {
                    runCatching { backupSource.deleteDbBlob(orphanUid, orphanDb) }
                }
            }
            Log.e(TAG, "Backup failed", e)
            _backupProgress.value = BackupProgress(
                stage = BackupStage.FAILED,
                errorMessage = e.message ?: "Backup failed",
            )
            Result.failure(e)
        } finally {
            snapshotFile.delete()
            gzipFile.delete()
            encDbFile.delete()
        }
    }

    override suspend fun restoreBackup(passphrase: String): Result<Unit> = operationMutex.withLock {
        val runTag = "${clock()}_${BackupCrypto.randomHexSuffix()}"
        val encDbFile = File(context.cacheDir, "restore_db_$runTag.enc")
        val gzDbFile = File(context.cacheDir, "restore_db_$runTag.db.gz")
        val plainDbFile = File(context.cacheDir, "restore_db_$runTag.db")
        var stagedDbFile: File? = null
        try {
            check(backupSource.isSupported) { "Encrypted backups are not supported on this backend" }
            val uid = authSource.currentUserId ?: error("Not signed in")

            // Stage 1: Fetch & parse manifest.
            _restoreProgress.value = RestoreProgress(stage = RestoreStage.FETCHING_MANIFEST, fraction = 0.08f)
            val rawJson = backupSource.fetchManifest(uid)
                ?: throw IOException("No backup found for this account")
            val manifest = BackupCrypto.parseManifest(rawJson, minIterations = minManifestIterations)

            // Stage 2: Derive key & verify passphrase against encryptedMetaB64 (fails fast).
            _restoreProgress.value = RestoreProgress(stage = RestoreStage.VERIFYING_PASSPHRASE, fraction = 0.18f)
            val salt = Base64.getDecoder().decode(manifest.saltB64)
            val masterKey = withContext(Dispatchers.Default) {
                BackupCrypto.deriveMasterKey(passphrase, salt, manifest.kdfIterations)
            }
            val inner = BackupCrypto.decryptManifestInner(manifest, masterKey)
            if (inner.schemaVersion > AppDatabase.CURRENT_VERSION) {
                throw UnsupportedBackupVersionException(
                    "This backup requires a newer version of FireStream (schema v${inner.schemaVersion}, app supports up to v${AppDatabase.CURRENT_VERSION})."
                )
            }

            // Stage 3: Download encrypted DB blob.
            _restoreProgress.value = RestoreProgress(stage = RestoreStage.DOWNLOADING_DB, fraction = 0.25f)
            backupSource.downloadDbBlob(uid, manifest.dbObjectName, encDbFile) { p ->
                _restoreProgress.value = RestoreProgress(
                    stage = RestoreStage.DOWNLOADING_DB,
                    fraction = 0.25f + 0.35f * p.coerceIn(0f, 1f),
                )
            }

            // Stage 4: Decrypt & SHA-256 verify, then gunzip.
            _restoreProgress.value = RestoreProgress(stage = RestoreStage.DECRYPTING_DB, fraction = 0.65f)
            withContext(Dispatchers.IO) {
                val dbKeyB64 = Base64.getEncoder().encodeToString(BackupCrypto.deriveDbKey(masterKey))
                MediaCrypto.decryptFile(
                    encryptedFile = encDbFile,
                    outputFile = gzDbFile,
                    keyB64 = dbKeyB64,
                    ivB64 = inner.dbIvB64,
                    expectedSha256B64 = inner.dbSha256B64,
                )
                encDbFile.delete()
                gunzipFile(gzDbFile, plainDbFile)
                gzDbFile.delete()
            }

            // Stage 5: Verify SQLite integrity & schema version, scrub unreadable/missing local paths,
            // and stage the copy inside databases/ BEFORE closing Room or persisting DataStore state.
            _restoreProgress.value = RestoreProgress(stage = RestoreStage.VERIFYING_DB, fraction = 0.80f)
            withContext(Dispatchers.IO) {
                verifyAndPrepareRestoredDb(plainDbFile)
                stagedDbFile = stageDatabaseCopy(plainDbFile)
            }

            // Stage 6: Close Room, atomically rename the pre-staged DB into place,
            // and persist key + backup metadata inside NonCancellable so cancellation
            // can never swap the DB without also persisting BackupKeyStore.
            _restoreProgress.value = RestoreProgress(stage = RestoreStage.REPLACING_DB, fraction = 0.90f)
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                    appDatabase.close()
                    commitStagedDatabase(stagedDbFile!!)
                }

                backupKeyStore.save(
                    StoredBackupKey(
                        masterKey = masterKey,
                        salt = salt,
                        kdfIterations = manifest.kdfIterations,
                    )
                )
                preferencesDataStore.recordBackupSuccess(
                    timestampMs = manifest.createdAt,
                    sizeBytes = manifest.dbCipherSizeBytes,
                    legacyMediaComplete = manifest.legacyMediaComplete,
                )
            }

            _restoreProgress.value = RestoreProgress(stage = RestoreStage.COMPLETED, fraction = 1f)
            Result.success(Unit)
        } catch (e: CancellationException) {
            _restoreProgress.value = RestoreProgress()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            val msg = when (e) {
                is WrongPassphraseException -> "Incorrect passphrase. Please try again."
                is UnsupportedBackupVersionException -> e.message
                else -> e.message ?: "Restore failed"
            }
            _restoreProgress.value = RestoreProgress(stage = RestoreStage.FAILED, errorMessage = msg)
            Result.failure(e)
        } finally {
            encDbFile.delete()
            gzDbFile.delete()
            plainDbFile.delete()
            stagedDbFile?.delete()
        }
    }

    private fun verifyAndPrepareRestoredDb(dbFile: File) {
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            val integrity = db.rawQuery("PRAGMA integrity_check", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            if (!"ok".equals(integrity, ignoreCase = true)) {
                throw IOException("Restored database failed integrity check: $integrity")
            }

            val userVersion = db.rawQuery("PRAGMA user_version", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
            if (userVersion <= 0) {
                throw IOException("Restored database has invalid schema version: $userVersion")
            }
            if (userVersion > AppDatabase.CURRENT_VERSION) {
                throw UnsupportedBackupVersionException(
                    "Backup schema version v$userVersion is newer than supported v${AppDatabase.CURRENT_VERSION}"
                )
            }

            val tables = mutableSetOf<String>()
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('messages', 'chats')",
                null,
            ).use { c ->
                while (c.moveToNext()) tables += c.getString(0)
            }
            if ("messages" !in tables || "chats" !in tables) {
                throw IOException("Restored database is missing required tables")
            }

            scrubMissingLocalPaths(db)
            db.rawQuery("PRAGMA journal_mode = DELETE", null).use { it.moveToFirst() }
        } finally {
            db.close()
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
            File("${dbFile.path}-journal").delete()
        }
    }

    private fun scrubMissingLocalPaths(db: SQLiteDatabase) {
        val hasLocalThumb = hasColumn(db, "messages", "localThumbUri")
        val missingLocalUriIds = mutableListOf<String>()
        val missingLocalThumbIds = mutableListOf<String>()
        val query = if (hasLocalThumb) {
            "SELECT id, localUri, localThumbUri FROM messages WHERE localUri IS NOT NULL OR localThumbUri IS NOT NULL"
        } else {
            "SELECT id, localUri, NULL FROM messages WHERE localUri IS NOT NULL"
        }
        db.rawQuery(query, null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val localUri = c.getString(1)
                val localThumb = c.getString(2)
                if (localUri != null && !isReadableFile(localUri)) {
                    missingLocalUriIds += id
                }
                if (localThumb != null && !isReadableFile(localThumb)) {
                    missingLocalThumbIds += id
                }
            }
        }

        val missingChatAvatars = collectUnreadablePaths(db, "chats", "id", "localAvatarPath")
        val missingUserAvatars = collectUnreadablePaths(db, "users", "uid", "localAvatarPath")
        val missingContactAvatars = collectUnreadablePaths(db, "contacts", "uid", "localAvatarPath")

        if (missingLocalUriIds.isEmpty() &&
            missingLocalThumbIds.isEmpty() &&
            missingChatAvatars.isEmpty() &&
            missingUserAvatars.isEmpty() &&
            missingContactAvatars.isEmpty()
        ) {
            return
        }
        db.beginTransaction()
        try {
            missingLocalUriIds.forEach { id ->
                db.execSQL("UPDATE messages SET localUri = NULL WHERE id = ?", arrayOf(id))
            }
            if (hasLocalThumb) {
                missingLocalThumbIds.forEach { id ->
                    db.execSQL("UPDATE messages SET localThumbUri = NULL WHERE id = ?", arrayOf(id))
                }
            }
            missingChatAvatars.forEach { id ->
                db.execSQL("UPDATE chats SET localAvatarPath = NULL WHERE id = ?", arrayOf(id))
            }
            missingUserAvatars.forEach { uid ->
                db.execSQL("UPDATE users SET localAvatarPath = NULL WHERE uid = ?", arrayOf(uid))
            }
            missingContactAvatars.forEach { uid ->
                db.execSQL("UPDATE contacts SET localAvatarPath = NULL WHERE uid = ?", arrayOf(uid))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun collectUnreadablePaths(
        db: SQLiteDatabase,
        table: String,
        idColumn: String,
        pathColumn: String,
    ): List<String> {
        if (!hasColumn(db, table, pathColumn)) return emptyList()
        val missing = mutableListOf<String>()
        db.rawQuery("SELECT $idColumn, $pathColumn FROM $table WHERE $pathColumn IS NOT NULL", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val path = c.getString(1)
                if (path != null && !isReadableFile(path)) {
                    missing += id
                }
            }
        }
        return missing
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIdx >= 0 && c.getString(nameIdx) == column) return true
            }
            false
        }

    /**
     * Checks both existence and actual open/readability. On Android 11+, shared
     * MediaStore files in `Pictures/FireStream Images/` from a prior install pass
     * `File.exists()` (and sometimes `canRead()`) yet fail with `EACCES` on open.
     */
    private fun isReadableFile(path: String?): Boolean {
        if (path == null) return false
        val file = File(path)
        if (!file.exists() || !file.isFile || !file.canRead()) return false
        return runCatching { file.inputStream().use { true } }.getOrDefault(false)
    }

    private fun stageDatabaseCopy(restoredDb: File): File {
        val target = context.getDatabasePath(databaseName)
        target.parentFile?.mkdirs()
        val staged = File(target.parentFile, "${target.name}.restore.tmp")
        restoredDb.inputStream().use { input ->
            staged.outputStream().use { output -> input.copyTo(output, BUFFER_SIZE) }
        }
        return staged
    }

    private fun commitStagedDatabase(staged: File) {
        val target = context.getDatabasePath(databaseName)
        File("${target.path}-wal").delete()
        File("${target.path}-shm").delete()
        File("${target.path}-journal").delete()
        if (!staged.renameTo(target)) {
            target.delete()
            if (!staged.renameTo(target)) {
                throw IOException("Failed to swap restored database into ${target.absolutePath}")
            }
        }
    }

    private fun sweepStaleTempFiles() {
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("backup_snapshot_") || file.name.startsWith("restore_db_")) {
                file.delete()
            }
        }
    }

    private fun gzipFile(input: File, output: File) {
        input.inputStream().use { i ->
            GZIPOutputStream(output.outputStream()).use { gz ->
                i.copyTo(gz, BUFFER_SIZE)
            }
        }
    }

    private fun gunzipFile(input: File, output: File) {
        GZIPInputStream(input.inputStream()).use { gz ->
            output.outputStream().use { o ->
                val buf = ByteArray(BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val n = gz.read(buf)
                    if (n == -1) break
                    total += n
                    if (total > MAX_UNCOMPRESSED_DB_BYTES) {
                        throw IOException("Restored database exceeds maximum allowed size")
                    }
                    o.write(buf, 0, n)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BackupManager"
        private const val DATABASE_NAME = "fire_stream_chat.db"
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_UNCOMPRESSED_DB_BYTES = 2L * 1024L * 1024L * 1024L // 2 GiB safety cap
    }
}
