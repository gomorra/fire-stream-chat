package com.firestream.chat.ui.chat.picker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.util.OverlayContent
import com.firestream.chat.domain.util.ShapeKind
import com.firestream.chat.ui.chat.imageedit.EditColorStrip
import com.firestream.chat.ui.chat.imageedit.drawOverlayShape

/**
 * The five annotation primitives, in the colour and fill the row below them sets.
 *
 * ### Why shapes are here at all
 *
 * "Put a box round this" is the other half of the blur tool's job
 * (`.claude/plans/image-editor.md` §3 Phase 5). A redaction that nobody notices
 * is a redaction nobody trusts, and pointing at the thing you covered is what
 * makes the covering legible. Both serve the same redact-before-sending purpose,
 * which is why they ship together.
 *
 * ### The style belongs to the host
 *
 * [colorArgb] and [filled] are the *host's* state, not this tab's: switching to
 * the text tab and back must not lose the red you chose, and a tab is disposed
 * when it stops being the active one. The overlay screen remembers them and
 * hands them down.
 *
 * Each button previews its shape with the same painter that draws a placed one,
 * so the colour and the fill you are about to get are the ones on the button.
 */
@Composable
internal fun ShapeTab(
    colorArgb: Long,
    filled: Boolean,
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
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShapeKind.entries.forEach { kind ->
                ShapeButton(
                    kind = kind,
                    colorArgb = colorArgb,
                    filled = filled,
                    onClick = {
                        onSelection(
                            PickerSelection.Overlay(OverlayContent.Shape(kind, colorArgb, filled)),
                        )
                    },
                )
            }
        }

        PickerStyleToggle(
            outlineLabel = "Outline",
            filledLabel = "Filled",
            filled = filled,
            onFilled = onFilled,
        )
        EditColorStrip(selected = colorArgb, onSelect = onColor, label = "Shape colours")
    }
}

/** One shape, previewed at the colour and fill it will be placed with. */
@Composable
private fun ShapeButton(
    kind: ShapeKind,
    colorArgb: Long,
    filled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(60.dp)
            .clickable(onClickLabel = "Place shape", onClick = onClick)
            .semantics { contentDescription = shapeName(kind) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(52.dp)) {
            drawOverlayShape(
                shape = OverlayContent.Shape(kind, colorArgb, filled),
                center = Offset(size.width / 2f, size.height / 2f),
                // Sized against the button's *width*, since every shape is wider
                // than it is tall — a size taken from the height would run the
                // arrow off both ends of its own preview.
                size = size.width / 2.2f,
            )
        }
    }
}

/** The name a screen reader reads for a shape, and the handle a test grabs it by. */
private fun shapeName(kind: ShapeKind): String = when (kind) {
    ShapeKind.RECTANGLE -> "Rectangle"
    ShapeKind.ROUNDED_RECTANGLE -> "Rounded rectangle"
    ShapeKind.ELLIPSE -> "Ellipse"
    ShapeKind.LINE -> "Line"
    ShapeKind.ARROW -> "Arrow"
}
