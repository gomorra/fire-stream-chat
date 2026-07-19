package com.firestream.chat.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Chip height is ~26 dp (bodyMedium 14 sp × EMOJI_INLINE_SCALE 1.3 × 1.2 + 2 dp×2 vertical
// padding), so 5 dp overlap is ~18%. Fixed dp (not a fraction of measured height) so a wrapped
// two-line FlowRow still only overlaps by one chip's worth.
private val REACTION_OVERLAP = 5.dp

// A plain Modifier.offset is draw-only and would leave phantom empty space below the row where
// it used to sit; this reduces the reported layout height AND shifts the placement up by the
// same amount, so the row visually overlaps the bubble's bottom edge with no leftover gap.
private fun Modifier.overlapAbove(amount: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val overlapPx = amount.roundToPx()
    layout(placeable.width, (placeable.height - overlapPx).coerceAtLeast(0)) {
        placeable.place(0, -overlapPx)
    }
}

/**
 * Transparent per-emoji reaction chips. Rendered as a later sibling of the message bubble in
 * the enclosing Column, so it draws on top of the bubble in the overlap strip — Compose doesn't
 * clip siblings by default. Renders nothing when [reactions] is empty.
 *
 * @param reactions userId → emoji, i.e. [com.firestream.chat.domain.model.Message.reactions].
 * @param onLongClick when non-null, chips use [combinedClickable] for both click and long-click;
 *   otherwise chips use a plain [clickable] with [onClick] only.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ReactionRow(
    reactions: Map<String, String>,
    currentUserId: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val groupedReactions = remember(reactions) {
        reactions.values
            .groupBy { it }
            .mapValues { it.value.size }
    }
    if (groupedReactions.isEmpty()) return

    val reactionFontSize = MaterialTheme.typography.bodyMedium.fontSize * EMOJI_INLINE_SCALE * 1.2f
    FlowRow(
        modifier = Modifier
            .overlapAbove(REACTION_OVERLAP)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        groupedReactions.forEach { (emoji, count) ->
            val myReaction = reactions[currentUserId] == emoji
            Text(
                text = if (count > 1) "$emoji $count" else emoji,
                fontSize = reactionFontSize.value.sp,
                color = if (myReaction) MaterialTheme.colorScheme.primary
                    else Color.Unspecified,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .let { chipModifier ->
                        if (onLongClick != null) {
                            chipModifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                        } else {
                            chipModifier.clickable(onClick = onClick)
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}
