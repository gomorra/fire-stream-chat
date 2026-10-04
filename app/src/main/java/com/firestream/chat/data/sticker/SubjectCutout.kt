package com.firestream.chat.data.sticker

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.firestream.chat.data.util.rethrowIfCancellation
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cuts the subject out of a photo with ML Kit's subject segmentation.
 *
 * The model is not in the APK. Play services delivers it, at install through
 * the manifest's `com.google.mlkit.vision.DEPENDENCIES` entry, or at the first
 * cutout, which then waits for the download for at most [MODEL_WAIT_MS].
 *
 * The API is a beta. Every failure is answered with `null`, and the maker then
 * offers the crop without a cutout.
 */
@Singleton
class SubjectCutout @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * The subject of [photo] on a transparent background, the same size as
     * [photo]. `null` without Play services, when the model does not arrive in
     * time, and when ML Kit fails or finds no subject.
     */
    suspend fun cutOut(photo: Bitmap): Bitmap? {
        try {
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
                return null
            }
            val segmenter = SubjectSegmentation.getClient(
                SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
            )
            try {
                return withTimeoutOrNull(MODEL_WAIT_MS) {
                    val modules = ModuleInstall.getClient(context)
                    val request = ModuleInstallRequest.newBuilder().addApi(segmenter).build()
                    // The call returns once the download is asked for, not once it is done.
                    if (!modules.installModules(request).await().areModulesAlreadyInstalled()) {
                        while (!modules.areModulesAvailable(segmenter).await().areModulesAvailable()) delay(MODEL_POLL_MS)
                    }
                    segmenter.process(InputImage.fromBitmap(photo, 0)).await().foregroundBitmap
                }
            } finally {
                segmenter.close()
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Log.w(TAG, "No cutout", e)
            return null
        }
    }

    private companion object {
        const val TAG = "SubjectCutout"
        const val MODEL_WAIT_MS = 45_000L
        const val MODEL_POLL_MS = 1_000L
    }
}
