package com.firestream.chat.ui.components

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

/**
 * Returns the best available image model for an avatar: prefers [localAvatarPath] if it is a
 * readable file, falls back to [avatarUrl], or null if neither is available.
 *
 * The readability check matches the message bubble's: a file that exists but can't be opened
 * would otherwise commit Coil to a load that fails, with no fallback to the URL.
 *
 * Wrap in [remember] with keys [localAvatarPath] and [avatarUrl] at the call site.
 */
fun resolveAvatarModel(localAvatarPath: String?, avatarUrl: String?): Any? =
    localAvatarPath
        ?.let(::File)
        ?.takeIf { it.isFile && it.canRead() }
        ?: avatarUrl

/**
 * Stable Coil cache key for an avatar, or null when there's no image.
 *
 * The key must stay identical whether we load from the local [File] or the remote
 * [avatarUrl] (so repeated appearances hit Coil's memory cache instantly), yet it must
 * change when the photo changes (so a new photo reloads). [avatarUrl] satisfies both:
 * Firebase Storage rotates its `?token=` on every re-upload, so the URL string is a
 * stable-but-change-sensitive identity. The local file path is NOT usable — it is
 * `<id>.jpg`, reused across uploads, so keying on it would serve a stale bitmap after a
 * photo change. Fall back to [localAvatarPath] only when no URL is known.
 */
fun avatarCacheKey(localAvatarPath: String?, avatarUrl: String?): String? =
    avatarUrl ?: localAvatarPath

/**
 * Builds a keyed Coil [ImageRequest] for an avatar, or null when there's no image (the
 * caller renders a letter/icon placeholder).
 *
 * The memory and disk cache keys are the stable [avatarCacheKey]. Coil then serves a warm
 * decoded bitmap on the first composition frame, and reloads only when the photo changes.
 *
 * The request decodes through [ScaledImageDecoder]. Avatars uploaded before upload-time
 * scaling are full camera originals. Coil's default `BitmapFactory` decode reaches avatar
 * size by a heavy power-of-two subsample, and that decode returns a black bitmap for them.
 */
fun buildAvatarRequest(context: Context, localAvatarPath: String?, avatarUrl: String?): ImageRequest? {
    val data = resolveAvatarModel(localAvatarPath, avatarUrl) ?: return null
    val key = avatarCacheKey(localAvatarPath, avatarUrl)
    return ImageRequest.Builder(context)
        .data(data)
        .memoryCacheKey(key)
        .diskCacheKey(key)
        .decoderFactory(ScaledImageDecoder.Factory())
        .crossfade(true)
        .build()
}

/** [buildAvatarRequest], remembered per [localAvatarPath] and [avatarUrl]. */
@Composable
fun rememberAvatarRequest(localAvatarPath: String?, avatarUrl: String?): ImageRequest? {
    val context = LocalContext.current
    return remember(localAvatarPath, avatarUrl) {
        buildAvatarRequest(context, localAvatarPath, avatarUrl)
    }
}

/** Where [AvatarImage] is loading from. It tries the local file first. */
internal enum class AvatarSource { LOCAL, REMOTE, NONE }

/**
 * The source to try after a load fails. A failed local file falls back to [avatarUrl]. A
 * failed URL, or a local file with no URL behind it, gives up and shows the placeholder.
 */
internal fun avatarSourceAfterError(failedOnLocalFile: Boolean, avatarUrl: String?): AvatarSource =
    if (failedOnLocalFile && avatarUrl != null) AvatarSource.REMOTE else AvatarSource.NONE

/**
 * An avatar image, or [placeholder] when there is none or it cannot be loaded.
 *
 * A local file that fails to load is retried from [avatarUrl]. That covers a cache file that
 * exists but can't be opened. Each failure is logged under the `AvatarImage` tag.
 */
@Composable
fun AvatarImage(
    localAvatarPath: String?,
    avatarUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit,
) {
    var source by remember(localAvatarPath, avatarUrl) { mutableStateOf(AvatarSource.LOCAL) }
    val request = rememberAvatarRequest(
        localAvatarPath = localAvatarPath.takeIf { source == AvatarSource.LOCAL },
        avatarUrl = avatarUrl.takeIf { source != AvatarSource.NONE },
    )

    if (request == null) {
        placeholder()
        return
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        onError = { error ->
            val failedOnLocalFile = error.result.request.data is File
            Log.w(
                TAG,
                "Avatar failed to load from the ${if (failedOnLocalFile) "local file" else "URL"}",
                error.result.throwable,
            )
            source = avatarSourceAfterError(failedOnLocalFile, avatarUrl)
        },
        modifier = modifier,
    )
}

private const val TAG = "AvatarImage"

@Composable
fun UserAvatar(
    avatarUrl: String?,
    contentDescription: String?,
    icon: ImageVector,
    size: Dp,
    modifier: Modifier = Modifier,
    localAvatarPath: String? = null
) {
    AvatarImage(
        localAvatarPath = localAvatarPath,
        avatarUrl = avatarUrl,
        contentDescription = contentDescription,
        modifier = modifier.clip(CircleShape),
    ) {
        Surface(
            modifier = modifier,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(size / 4.5f),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
