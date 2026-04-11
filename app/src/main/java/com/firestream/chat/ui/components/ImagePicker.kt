package com.firestream.chat.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * State holder returned by [rememberImagePicker]. Call [pickFromGallery] to
 * launch the photo picker or [captureFromCamera] to request the camera
 * (handling the CAMERA permission prompt automatically).
 */
@Stable
class ImagePickerState internal constructor(
    private val onPickFromGallery: () -> Unit,
    private val onCaptureFromCamera: () -> Unit,
) {
    fun pickFromGallery() = onPickFromGallery()
    fun captureFromCamera() = onCaptureFromCamera()
}

/**
 * Bundles the three activity-result launchers that every "upload an image"
 * flow in the app needs: a gallery picker, a camera capture, and a CAMERA
 * permission request that chains into the capture on grant.
 *
 * Replaces the ~25-line launcher scaffolding that was previously duplicated
 * across `ChatScreen`, `ProfileScreen`, and `GroupSettingsScreen`.
 *
 * @param createCameraUri Called each time a camera capture starts to produce
 *   a fresh `content://` URI (typically via [FileProvider]) for the camera
 *   activity to write to. Use [cameraCacheUri] for the common case.
 * @param onImagePicked Invoked with the selected/captured image URI. Called
 *   once per successful pick; not called on cancel or permission denial.
 */
@Composable
fun rememberImagePicker(
    createCameraUri: (Context) -> Uri,
    onImagePicked: (Uri) -> Unit,
): ImagePickerState {
    val context = LocalContext.current
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val currentOnPicked by rememberUpdatedState(onImagePicked)
    val currentCreateCameraUri by rememberUpdatedState(createCameraUri)

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let(currentOnPicked)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) cameraUri?.let(currentOnPicked)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = currentCreateCameraUri(context)
            cameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    return remember(galleryLauncher, cameraLauncher, cameraPermissionLauncher) {
        ImagePickerState(
            onPickFromGallery = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onCaptureFromCamera = {
                val cameraGranted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (cameraGranted) {
                    val uri = currentCreateCameraUri(context)
                    cameraUri = uri
                    cameraLauncher.launch(uri)
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        )
    }
}

/**
 * Default camera URI factory — writes to `cacheDir/camera/<prefix>_<ts>.jpg`
 * via [FileProvider]. Use with [rememberImagePicker]'s `createCameraUri`
 * parameter.
 */
fun cameraCacheUri(context: Context, filenamePrefix: String = "photo"): Uri {
    val cacheDir = File(context.cacheDir, "camera").also { it.mkdirs() }
    val file = File(cacheDir, "${filenamePrefix}_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
