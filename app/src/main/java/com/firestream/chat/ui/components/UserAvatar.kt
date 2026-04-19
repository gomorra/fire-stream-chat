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
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
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

@Composable
fun UserAvatar(
    avatarUrl: String?,
    contentDescription: String?,
    icon: ImageVector,
    size: Dp,
    modifier: Modifier = Modifier,
    localAvatarPath: String? = null
) {
    val imageModel = remember(localAvatarPath, avatarUrl) {
        resolveAvatarModel(localAvatarPath, avatarUrl)
    }

    if (imageModel != null) {
        AsyncImage(
            model = imageModel,
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
