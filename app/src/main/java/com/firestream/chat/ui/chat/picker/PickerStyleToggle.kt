package com.firestream.chat.ui.chat.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.firestream.chat.ui.theme.FireOrange
import com.firestream.chat.ui.theme.FsText

/**
 * Two labelled halves, one of them lit — the fill-or-outline choice the text and
 * shape tabs both offer.
 *
 * **Two options rather than a switch**, because neither is the "on" state: an
 * outlined box round a face and a filled block over it are different tools, and
 * a switch would imply one of them is the plain version of the other. The same
 * argument applies to solid versus hollow lettering.
 *
 * One composable rather than one per tab because it is literally one control
 * appearing twice: the tabs set the same `filled` flag on the same
 * `OverlayContent`, and two copies would be two chances for the two tabs to
 * disagree about what the choice looks like.
 */
@Composable
internal fun PickerStyleToggle(
    /** The label for `filled = false`. */
    outlineLabel: String,
    /** The label for `filled = true`. */
    filledLabel: String,
    filled: Boolean,
    onFilled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StyleOption(label = outlineLabel, selected = !filled, onClick = { onFilled(false) })
        StyleOption(label = filledLabel, selected = filled, onClick = { onFilled(true) })
    }
}

@Composable
private fun StyleOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                color = if (selected) FireOrange.copy(alpha = 0.2f) else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) FireOrange else FsText,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
