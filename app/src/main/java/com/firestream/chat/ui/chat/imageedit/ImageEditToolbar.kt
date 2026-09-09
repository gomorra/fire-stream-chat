package com.firestream.chat.ui.chat.imageedit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firestream.chat.ui.theme.FireOrange
import com.firestream.chat.ui.theme.FsText
import com.firestream.chat.ui.theme.FsTextMute

/**
 * The editor's overlay rail: the controls that float over the photo in
 * `ImagePreviewScreen`.
 *
 * Two clusters, deliberately apart. [ImageEditActions] is the *tools* — HD,
 * adjust, overlay, draw, download — pinned top-right opposite the back arrow.
 * [ImageEditHistory] is undo / redo / original⇄edited, and it sits top-left
 * under the back arrow because it acts on what the tools have already done. It
 * is **hidden, not disabled, while the item has no history at all**: a fresh
 * pick should look untouched, not greyed-out, and there is nothing yet for it
 * to act on.
 *
 * The three editor entry points are nullable actions and render dimmed and
 * unclickable when null, which is how the phases stage in: Phase 1 wires HD and
 * download and leaves them dark until the screens behind them exist. A video
 * page passes `showEditTools = false` and a null [isHd] instead — neither
 * applies to a video, and a dimmed control the feature will never light up is a
 * different statement from one that is merely not built yet.
 *
 * The controls are black-scrim circles rather than a bar so the photo stays
 * uncovered; the 40 dp tool circle and its 22 dp glyph match the back arrow
 * already on this screen. The history pill's buttons are 48 dp instead — see
 * [ImageEditHistory].
 */
@Composable
internal fun ImageEditActions(
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    isHd: Boolean? = null,
    onToggleHd: () -> Unit = {},
    showEditTools: Boolean = true,
    onAdjust: (() -> Unit)? = null,
    onOverlay: (() -> Unit)? = null,
    onDraw: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isHd != null) HdPill(isHd = isHd, onClick = onToggleHd)
        if (showEditTools) {
            ScrimIconButton(Icons.Default.Crop, "Adjust", onAdjust)
            ScrimIconButton(Icons.Outlined.EmojiEmotions, "Add stickers or text", onOverlay)
            ScrimIconButton(Icons.Default.Edit, "Draw", onDraw)
        }
        ScrimIconButton(Icons.Default.FileDownload, "Save to Downloads", onDownload)
    }
}

/**
 * Undo / redo over the edit steps, and the original⇄edited jump between the two
 * ends of that same axis (`.claude/plans/image-editor.md` §2.7). One pill, so
 * the three read as one history control rather than three more tools.
 */
@Composable
internal fun ImageEditHistory(
    canUndo: Boolean,
    canRedo: Boolean,
    showingOriginal: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleOriginal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        // 48 dp tall, unlike the 40 dp tool circles opposite: these three sit
        // shoulder to shoulder inside one pill with no gap between their hit
        // rects, so anything smaller would put three sub-target buttons in a row
        // and make a mis-tap an undo rather than a miss.
        modifier = modifier
            .height(48.dp)
            .background(color = Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(24.dp))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillIconButton(Icons.AutoMirrored.Filled.Undo, "Undo edit", enabled = canUndo, onClick = onUndo)
        PillIconButton(Icons.AutoMirrored.Filled.Redo, "Redo edit", enabled = canRedo, onClick = onRedo)
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(20.dp)
                .background(Color.White.copy(alpha = 0.12f))
        )
        PillIconButton(
            icon = Icons.Default.SwapHoriz,
            contentDescription = if (showingOriginal) "Show edited image" else "Show original image",
            enabled = true,
            selected = showingOriginal,
            onClick = onToggleOriginal,
        )
    }
}

/**
 * The HD switch. A labelled pill rather than an icon because "HD" is the word
 * the badge on a sent message already uses, and because it carries a state the
 * user has to be able to read at a glance rather than infer from a tint.
 */
@Composable
private fun HdPill(isHd: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .defaultMinSize(minWidth = 40.dp)
            .background(
                color = if (isHd) FireOrange else Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(
                onClickLabel = if (isHd) "Turn HD off" else "Turn HD on",
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "HD",
            style = MaterialTheme.typography.labelSmall,
            color = if (isHd) Color.White else FsText,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

/** A 40 dp scrim circle; dimmed and unclickable when [onClick] is null. */
@Composable
private fun ScrimIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: (() -> Unit)?,
) {
    IconButton(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = Modifier
            .size(40.dp)
            .background(color = Color.Black.copy(alpha = 0.5f), shape = CircleShape),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (onClick != null) FsText else FsTextMute,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** A 48 dp transparent button for use inside the history pill's own scrim. */
@Composable
private fun PillIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> FsTextMute
                selected -> FireOrange
                else -> FsText
            },
            modifier = Modifier.size(20.dp),
        )
    }
}
