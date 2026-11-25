// region: AGENT-NOTE
// Responsibility: Downloads and decrypts backed-up legacy plaintext media blobs
//   (`backups/{uid}/legacy-media/{messageId}.enc`) using the locally stored
//   backup master key. Used as a fallback by MediaFileManager when a legacy
//   media URL is unreachable (e.g., after Step 9 scrubs legacy Storage).
// Collaborators: BackupSource, BackupKeyStore, AuthSource, BackupCrypto,
//   MediaFileManager. Kept separate from BackupManager to avoid a DI cycle.
// endregion

package com.firestream.chat.data.backup

import android.content.Context
import android.util.Log
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.util.rethrowIfCancellation
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LegacyMediaBackupFetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupSource: BackupSource,
    private val backupKeyStore: BackupKeyStore,
    private val authSource: AuthSource,
) {
    /**
     * Attempts to download `backups/{uid}/legacy-media/{messageId}.enc` and
     * decrypt it into [destFile]. Returns `true` when the blob was found and
     * decrypted cleanly, `false` when backups are unsupported, unconfigured, or
     * the blob does not exist (or fails to decrypt).
     */
    suspend fun downloadAndDecrypt(messageId: String, destFile: File): Boolean {
        if (!backupSource.isSupported) return false
        val uid = authSource.currentUserId ?: return false
        val storedKey = backupKeyStore.load() ?: return false
        val encTemp = File.createTempFile("legacy_bak_${messageId}_", ".enc.part", context.cacheDir)
        return try {
            val found = backupSource.downloadLegacyMediaBlob(uid, storedKey.keyGeneration, messageId, encTemp)
            if (!found) return false
            BackupCrypto.decryptLegacyMediaFile(encTemp, destFile, storedKey.masterKey, messageId)
            true
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Log.w(TAG, "Legacy media backup fallback failed for msg=$messageId", e)
            destFile.delete()
            false
        } finally {
            encTemp.delete()
        }
    }

    companion object {
        private const val TAG = "LegacyMediaBackupFetcher"
    }
}
