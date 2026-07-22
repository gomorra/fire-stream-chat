package com.firestream.chat.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * Returns the best available image model for an avatar: prefers [localAvatarPath] if the file
 * exists, falls back to [avatarUrl], or null if neither is available.
 *
 * Wrap in [remember] with keys [localAvatarPath] and [avatarUrl] at the call site.
 */
fun resolveAvatarModel(localAvatarPath: String?, avatarUrl: String?): Any? =
    if (localAvatarPath != null) {
        val file = File(localAvatarPath)
        if (file.exists()) file else avatarUrl
    } else avatarUrl

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
 * caller renders a letter/icon placeholder). The [memoryCacheKey]/[diskCacheKey] are the
 * stable [avatarCacheKey], which lets Coil serve a warm decoded bitmap on the first
 * composition frame — eliminating the blank-then-pop flash — and reload only when the
 * photo actually changes.
 */
@Composable
fun rememberAvatarRequest(localAvatarPath: String?, avatarUrl: String?): ImageRequest? {
    val context = LocalContext.current
    return remember(localAvatarPath, avatarUrl) {
        val data = resolveAvatarModel(localAvatarPath, avatarUrl) ?: return@remember null
        val key = avatarCacheKey(localAvatarPath, avatarUrl)
        ImageRequest.Builder(context)
            .data(data)
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .crossfade(true)
            .build()
    }
}

@Composable
fun UserAvatar(
    avatarUrl: String?,
    contentDescription: String?,
    icon: ImageVector,
    size: Dp,
    modifier: Modifier = Modifier,
    localAvatarPath: String? = null
) {
    val request = rememberAvatarRequest(localAvatarPath, avatarUrl)

    if (request != null) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .clip(CircleShape)
        )
    } else {
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
