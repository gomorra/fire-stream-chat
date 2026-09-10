package com.firestream.chat.ui.chat.imageedit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firestream.chat.ui.theme.FireOrange
import com.firestream.chat.ui.theme.FsText
import com.firestream.chat.ui.theme.FsTextMute

/**
 * The chrome every editor screen wears: the top bar, the tool buttons, the
 * failure banner and the scrim that covers a flatten in flight.
 *
 * Shared rather than written per screen because it is not per-screen design —
 * [AdjustImageScreen] and [DrawImageScreen] are the same full-screen editor
 * shape with different middles, and the pieces here were byte-for-byte
 * identical in both before this file existed. What each screen keeps is what
 * genuinely differs: its photo surface, its tool panel, and the words on its
 * buttons.
 */

/** The heights both editor screens lay their chrome out against. */
internal object EditorChrome {
    const val TOP_BAR_HEIGHT_DP = 56
    const val TOOL_ROW_HEIGHT_DP = 68
}

/**
 * What an editor's top bar calls its four actions.
 *
 * Bundled rather than passed as four `String` parameters, for the reason
 * [AdjustCallbacks] and [DrawCallbacks] exist: the generated signature of a
 * Composable is what ART's ~15-parameter ceiling counts, and it throws
 * `VerifyError` **on first render** rather than at compile time. The words
 * differ per screen because they are read aloud — "Undo stroke" and "Undo
 * adjustment" are different promises — and the tests grab the buttons by them.
 */
@Immutable
internal data class EditTopBarLabels(
    val cancel: String,
    val undo: String,
    val redo: String,
    val done: String,
)

/**
 * Cancel and the history controls at one end, Done at the other, and whatever
 * the screen wants in between.
 *
 * [middleContent] is the slot the adjust screen puts its Reset button in and the
 * draw screen leaves empty — a slot rather than a nullable parameter because a
 * slot costs one parameter however much goes in it.
 */
@Composable
internal fun EditTopBar(
    labels: EditTopBarLabels,
    canUndo: Boolean,
    canRedo: Boolean,
    enabled: Boolean,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    middleContent: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(EditorChrome.TOP_BAR_HEIGHT_DP.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel, enabled = enabled) {
            Icon(Icons.Default.Close, contentDescription = labels.cancel, tint = FsText)
        }

        Spacer(modifier = Modifier.width(4.dp))

        IconButton(onClick = onUndo, enabled = enabled && canUndo) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Undo,
                contentDescription = labels.undo,
                tint = editTint(enabled = enabled && canUndo),
            )
        }
        IconButton(onClick = onRedo, enabled = enabled && canRedo) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Redo,
                contentDescription = labels.redo,
                tint = editTint(enabled = enabled && canRedo),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        middleContent()

        IconButton(onClick = onDone, enabled = enabled) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = labels.done,
                tint = if (enabled) FireOrange else FsTextMute,
            )
        }
    }
}

/** One tool in an editor's bottom row: glyph over label, tinted by its state. */
@Composable
internal fun EditToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    width: Dp = 64.dp,
) {
    val tint = editTint(enabled = enabled, selected = selected)
    Column(
        modifier = Modifier
            .size(width = width, height = EditorChrome.TOOL_ROW_HEIGHT_DP.dp)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * The one tint rule the editors use for every control: muted when it cannot be
 * pressed, brand orange when it is the one in effect, ordinary text otherwise.
 */
@Composable
internal fun editTint(enabled: Boolean, selected: Boolean = false): Color = when {
    !enabled -> FsTextMute
    selected -> FireOrange
    else -> FsText
}

/** "Couldn't apply …" — the line an editor shows when a flatten came back empty. */
@Composable
internal fun EditFailureBanner(visible: Boolean, message: String) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/**
 * The half-transparent cover over an editor while its Done is flattening.
 *
 * The visual and [swallowStrayGestures] travel together deliberately: the
 * controls underneath are already disabled, but they stay visible through the
 * scrim and hit-testable as far as the layout is concerned, and on the draw
 * screen a stray pointer would not merely press a dead button — it would draw a
 * stroke onto a drawing already being written.
 */
@Composable
internal fun BoxScope.EditFlattenScrim() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .swallowStrayGestures(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = FireOrange)
    }
}

/**
 * Swallows every pointer event that reaches the flatten scrim. Main pass, so
 * anything the scrim's own content wants still gets first refusal.
 */
private fun Modifier.swallowStrayGestures(): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Main).changes.forEach { change ->
                    if (change.pressed || change.previousPressed) change.consume()
                }
            }
        }
    },
)
