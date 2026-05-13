package com.firestream.chat.ui.lists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.User
import com.firestream.chat.ui.components.UserAvatar

private val AVATAR_SIZE = 20.dp
private val AVATAR_STEP = 12.dp // AVATAR_SIZE - overlap(8dp)

@Composable
internal fun AvatarStack(users: List<User>, overflow: Int, modifier: Modifier = Modifier) {
    if (users.isEmpty() && overflow == 0) return
    val slotCount = users.size + if (overflow > 0) 1 else 0
    val totalWidth = AVATAR_SIZE + AVATAR_STEP * (slotCount - 1).coerceAtLeast(0)
    Box(modifier = modifier.width(totalWidth)) {
        users.forEachIndexed { index, user ->
            UserAvatar(
                avatarUrl = user.avatarUrl,
                contentDescription = user.displayName,
                icon = Icons.Default.Person,
                size = AVATAR_SIZE,
                modifier = Modifier
                    .size(AVATAR_SIZE)
                    .offset(x = AVATAR_STEP * index),
                localAvatarPath = user.localAvatarPath
            )
        }
        if (overflow > 0) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .size(AVATAR_SIZE)
                    .offset(x = AVATAR_STEP * users.size)
                    .align(Alignment.CenterStart)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "+$overflow",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}
