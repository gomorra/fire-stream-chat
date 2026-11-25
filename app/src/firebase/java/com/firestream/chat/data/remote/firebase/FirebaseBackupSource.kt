// region: AGENT-NOTE
// Responsibility: Firebase Storage implementation of BackupSource storing
//   passphrase-encrypted backups under `backups/{uid}/`:
//   - `manifest.json` (application/json)
//   - `db_<timestamp>_<hex>.enc` (application/octet-stream)
//   - `legacy-media/{keyGen}/{messageId}.enc` (application/octet-stream)
// Collaborators: BackupManager, LegacyMediaBackupFetcher, FirebaseSourceBindings.
// endregion

package com.firestream.chat.data.remote.firebase

import android.net.Uri
import com.firestream.chat.data.backup.BackupSource
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class FirebaseBackupSource @Inject constructor(
    private val storage: FirebaseStorage,
) : BackupSource {

    override val isSupported: Boolean = true

    override suspend fun fetchManifest(uid: String): String? {
        val ref = storage.reference.child("backups/$uid/$MANIFEST_NAME")
        return try {
            val bytes = ref.getBytes(MAX_MANIFEST_BYTES).await()
            String(bytes, StandardCharsets.UTF_8)
        } catch (e: StorageException) {
            if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) null else throw e
        }
    }

    override suspend fun uploadManifest(uid: String, json: String) {
        val ref = storage.reference.child("backups/$uid/$MANIFEST_NAME")
        val metadata = StorageMetadata.Builder()
            .setContentType("application/json")
            .build()
        ref.putBytes(json.toByteArray(StandardCharsets.UTF_8), metadata).await()
    }

    override suspend fun uploadDbBlob(
        uid: String,
        objectName: String,
        file: File,
        onProgress: ((Float) -> Unit)?,
    ) {
        val ref = storage.reference.child("backups/$uid/$objectName")
        val metadata = StorageMetadata.Builder()
            .setContentType("application/octet-stream")
            .build()
        suspendCancellableCoroutine { cont ->
            val task = ref.putFile(Uri.fromFile(file), metadata)
            if (onProgress != null) {
                task.addOnProgressListener { snap ->
                    if (snap.totalByteCount > 0) {
                        onProgress(snap.bytesTransferred.toFloat() / snap.totalByteCount.toFloat())
                    }
                }
            }
            task.addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
                .addOnCanceledListener {
                    if (cont.isActive) cont.resumeWithException(CancellationException("Backup DB upload canceled"))
                }
            cont.invokeOnCancellation { task.cancel() }
        }
    }

    override suspend fun downloadDbBlob(
        uid: String,
        objectName: String,
        destFile: File,
        onProgress: ((Float) -> Unit)?,
    ) {
        val ref = storage.reference.child("backups/$uid/$objectName")
        suspendCancellableCoroutine { cont ->
            val task = ref.getFile(destFile)
            if (onProgress != null) {
                task.addOnProgressListener { snap ->
                    if (snap.totalByteCount > 0) {
                        onProgress(snap.bytesTransferred.toFloat() / snap.totalByteCount.toFloat())
                    }
                }
            }
            task.addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
                .addOnCanceledListener {
                    if (cont.isActive) cont.resumeWithException(CancellationException("Backup DB download canceled"))
                }
            cont.invokeOnCancellation { task.cancel() }
        }
    }

    override suspend fun deleteDbBlob(uid: String, objectName: String) {
        val ref = storage.reference.child("backups/$uid/$objectName")
        try {
            ref.delete().await()
        } catch (e: StorageException) {
            if (e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw e
        }
    }

    override suspend fun uploadLegacyMediaBlob(uid: String, keyGen: String, messageId: String, file: File) {
        val ref = storage.reference.child("backups/$uid/$LEGACY_MEDIA_DIR/$keyGen/$messageId.enc")
        val metadata = StorageMetadata.Builder()
            .setContentType("application/octet-stream")
            .build()
        ref.putFile(Uri.fromFile(file), metadata).await()
    }

    override suspend fun downloadLegacyMediaBlob(
        uid: String,
        keyGen: String,
        messageId: String,
        destFile: File,
    ): Boolean {
        val ref = storage.reference.child("backups/$uid/$LEGACY_MEDIA_DIR/$keyGen/$messageId.enc")
        return try {
            ref.getFile(destFile).await()
            true
        } catch (e: StorageException) {
            if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) {
                destFile.delete()
                false
            } else {
                throw e
            }
        }
    }

    override suspend fun deleteLegacyMediaBlob(uid: String, keyGen: String, messageId: String) {
        val ref = storage.reference.child("backups/$uid/$LEGACY_MEDIA_DIR/$keyGen/$messageId.enc")
        try {
            ref.delete().await()
        } catch (e: StorageException) {
            if (e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw e
        }
    }

    companion object {
        private const val MANIFEST_NAME = "manifest.json"
        private const val LEGACY_MEDIA_DIR = "legacy-media"
        private const val MAX_MANIFEST_BYTES = 10L * 1024L * 1024L
    }
}
