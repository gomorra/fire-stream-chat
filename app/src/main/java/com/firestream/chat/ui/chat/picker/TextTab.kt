package com.firestream.chat.ui.chat.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.util.OverlayContent
import com.firestream.chat.ui.chat.imageedit.EditColorStrip
import com.firestream.chat.ui.theme.FireOrange
import com.firestream.chat.ui.theme.FsTextMute

/**
 * Type a line, choose how it looks, and put it on the photo.
 *
 * ### One line, centred, and why there is an explicit Add
 *
 * The run is a single line: a text box that wraps needs a width, and the overlay
 * screen's scale handle is uniform, so there is nothing to express a wrap width
 * with (`.claude/plans/image-editor.md` §3 Phase 5 — "centre alignment"). One
 * line also means the preview and the flatten centre it identically, which
 * multi-line layout would put at risk.
 *
 * Placing happens on an explicit **Add** rather than on every keystroke, because
 * a keystroke is not a placement: typing "meet me here" would otherwise leave
 * twelve overlapping text runs on the photo, each one a separate undo step.
 *
 * ### The style belongs to the host
 *
 * [colorArgb] and [filled] come from the overlay screen for the reason
 * [ShapeTab]'s do: a tab is disposed when it stops being the active one, and
 * switching to stickers and back must not lose the colour you chose. The
 * *draft text* is held the same way, so a half-typed line survives a look at the
 * emoji tab.
 */
@Composable
internal fun TextTab(
    draft: String,
    colorArgb: Long,
    filled: Boolean,
    onDraft: (String) -> Unit,
    onColor: (Long) -> Unit,
    onFilled: (Boolean) -> Unit,
    onSelection: (PickerSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (draft.isEmpty()) {
                    Text(
                        text = "Type something to place",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                BasicTextField(
                    value = draft,
                    // A newline would be a second line the renderer cannot
                    // centre the same way twice, so it never reaches the draft.
                    onValueChange = { onDraft(it.replace("\n", " ")) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = Color(colorArgb),
                        fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Start,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Overlay text" },
                )
            }

            val ready = draft.isNotBlank()
            IconButton(
                onClick = {
                    onSelection(
                        PickerSelection.Overlay(
                            OverlayContent.Text(draft.trim(), colorArgb, filled),
                        ),
                    )
                    onDraft("")
                },
                enabled = ready,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Place text",
                    tint = if (ready) FireOrange else FsTextMute,
                )
            }
        }

        PickerStyleToggle(
            outlineLabel = "Outline",
            filledLabel = "Solid",
            filled = filled,
            onFilled = onFilled,
        )
        EditColorStrip(selected = colorArgb, onSelect = onColor, label = "Text colours")
    }
}
