// region: AGENT-NOTE
// Responsibility: Backend-neutral storage contract for per-user encrypted
//   backups (`backups/{uid}/manifest.json`, `db_<ts>_<hex>.enc`, and
//   `legacy-media/{keyGen}/{messageId}.enc`).
// Collaborators: FirebaseBackupSource (firebase flavor), PocketBaseBackupSource
//   (pocketbase stub), BackupManager, LegacyMediaBackupFetcher.
// endregion

package com.firestream.chat.data.backup

import java.io.File

interface BackupSource {
    val isSupported: Boolean

    suspend fun fetchManifest(uid: String): String?
    suspend fun uploadManifest(uid: String, json: String)
    suspend fun uploadDbBlob(uid: String, objectName: String, file: File, onProgress: ((Float) -> Unit)? = null)
    suspend fun downloadDbBlob(uid: String, objectName: String, destFile: File, onProgress: ((Float) -> Unit)? = null)
    suspend fun deleteDbBlob(uid: String, objectName: String)
    suspend fun uploadLegacyMediaBlob(uid: String, keyGen: String, messageId: String, file: File)
    suspend fun downloadLegacyMediaBlob(uid: String, keyGen: String, messageId: String, destFile: File): Boolean
    suspend fun deleteLegacyMediaBlob(uid: String, keyGen: String, messageId: String)
}
