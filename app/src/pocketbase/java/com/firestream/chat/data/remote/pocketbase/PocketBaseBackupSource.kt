// region: AGENT-NOTE
// Responsibility: PocketBase-flavor stub for BackupSource. Passphrase-protected
//   cloud backups target Firebase Storage only; PocketBase returns unsupported.
// endregion

package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.backup.BackupSource
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PocketBaseBackupSource @Inject constructor() : BackupSource {
    override val isSupported: Boolean = false

    override suspend fun fetchManifest(uid: String): String? = null

    override suspend fun uploadManifest(uid: String, json: String) {
        throw UnsupportedOperationException("Encrypted cloud backups are only supported on Firebase")
    }

    override suspend fun uploadDbBlob(uid: String, objectName: String, file: File, onProgress: ((Float) -> Unit)?) {
        throw UnsupportedOperationException("Encrypted cloud backups are only supported on Firebase")
    }

    override suspend fun downloadDbBlob(uid: String, objectName: String, destFile: File, onProgress: ((Float) -> Unit)?) {
        throw UnsupportedOperationException("Encrypted cloud backups are only supported on Firebase")
    }

    override suspend fun deleteDbBlob(uid: String, objectName: String) = Unit

    override suspend fun uploadLegacyMediaBlob(uid: String, keyGen: String, messageId: String, file: File) {
        throw UnsupportedOperationException("Encrypted cloud backups are only supported on Firebase")
    }

    override suspend fun downloadLegacyMediaBlob(
        uid: String,
        keyGen: String,
        messageId: String,
        destFile: File,
    ): Boolean = false

    override suspend fun deleteLegacyMediaBlob(uid: String, keyGen: String, messageId: String) = Unit
}
