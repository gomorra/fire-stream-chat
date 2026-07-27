package com.firestream.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.command.CommandMatch
import com.firestream.chat.domain.command.CommandPath

@Composable
internal fun CommandPalette(
    visible: Boolean,
    currentPath: CommandPath,
    filter: String,
    candidates: List<CommandMatch>,
    onCommandTap: (CommandMatch) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Set when the current level matched nothing and the registry fell back to the
    // subtree — the rows are then somewhere below the path in the header, so they
    // render as full paths and the header says where they came from.
    val showingNested = candidates.any { it.isDeep }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .shadow(elevation = 6.dp, shape = RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .heightIn(max = 280.dp)
        ) {
            CommandPaletteHeader(currentPath, showingNested)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (candidates.isEmpty()) {
                EmptyState(currentPath, filter)
            } else {
                LazyColumn {
                    // Keyed by full path, not id: a fallback search can surface two
                    // commands sharing a leaf id (`.timer.set` and `.alarm.set`), and
                    // duplicate keys throw in LazyColumn.
                    items(candidates, key = { it.path.displayString() }) { match ->
                        CommandRow(match, onClick = { onCommandTap(match) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandPaletteHeader(path: CommandPath, showingNested: Boolean) {
    val title = if (path.isRoot) "Commands" else path.displayString()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        if (showingNested) {
            Text(
                text = "Nested matches",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(path: CommandPath, filter: String) {
    // "No commands available" only fits a genuinely empty registry. Once the user has
    // typed something, an empty list means their search missed — at root as much as
    // anywhere else, and now that the search descends the subtree it means nothing in
    // the whole tree matched.
    val msg = if (path.isRoot && filter.isBlank()) "No commands available" else "No matches"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CommandRow(match: CommandMatch, onClick: () -> Unit) {
    val command = match.command
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            // A nested match shows its full path — its bare display name (".set")
            // wouldn't say which command it belongs to.
            text = if (match.isDeep) match.path.displayString() else command.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        command.description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
