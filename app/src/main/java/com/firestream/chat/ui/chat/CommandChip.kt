package com.firestream.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.command.CommandPath

/**
 * Pill rendering of the active `.command.subcommand` path in the composer
 * input row. Tapping it dismisses the active widget / palette so the user
 * can pop back to plain typing.
 */
@Composable
internal fun CommandChip(
    path: CommandPath,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (path.isRoot) return
    Text(
        text = path.displayString(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
