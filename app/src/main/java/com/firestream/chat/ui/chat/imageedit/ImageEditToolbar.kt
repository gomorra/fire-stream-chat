package com.firestream.chat.ui.chat.imageedit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
 * uncovered. Every one of them is the fullscreen viewer's control — a 36 dp
 * circle and 20 dp glyph inside a 48 dp touch target ([ScrimCircleButton]) —
 * and the two pills are 36 dp tall inside the same 48 dp, so the preview a
 * camera shot lands in and the viewer a received photo opens in read as one
 * surface. They were 40 dp circles with 40 dp targets before: a different size
 * from the viewer's, and short of the 48 dp a finger needs. The history pill's
 * buttons are 48 dp too — see [ImageEditHistory].
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
        // Each control's 48 dp target carries 6 dp of clear space either side
        // of its 36 dp visual; overlapping neighbours by 4 dp keeps an 8 dp gap
        // between visuals and the whole rail clear of the back arrow on a
        // 360 dp phone. A tap in the shared strip goes to the later control.
        horizontalArrangement = Arrangement.spacedBy((-4).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isHd != null) HdPill(isHd = isHd, onClick = onToggleHd)
        if (showEditTools) {
            ScrimCircleButton(Icons.Default.Crop, "Adjust", onAdjust)
            ScrimCircleButton(Icons.Outlined.EmojiEmotions, "Add stickers or text", onOverlay)
            ScrimCircleButton(Icons.Default.Edit, "Draw", onDraw)
        }
        ScrimCircleButton(Icons.Default.FileDownload, "Save to Downloads", onDownload)
    }
}

/**
 * Undo / redo over the edit steps, and the original⇄edited jump between the two
 * ends of that same axis (`docs/plans/image-editor.md` §2.7). One pill, so
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
    ScrimPill(
        onClickLabel = if (isHd) "Turn HD off" else "Turn HD on",
        onClick = onClick,
        selected = isHd,
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

/**
 * The crop-shape pill: a crop glyph and the current preset, and a tap cycles
 * to the next. One control that cycles rather than a row of presets because
 * it sits over the photo, and the presets are few enough that the second tap
 * is never far away. It lives bottom-left in both the send preview and the
 * fullscreen viewer, under the thumb and away from the top rail, which on the
 * preview is already five controls long and has no room for a sixth beside
 * the back arrow on a 360 dp phone. Lit like the HD pill once a shape other
 * than Free is chosen, so an active crop is visible even before the frame is.
 */
@Composable
internal fun CropAspectPill(aspect: CropAspect, onClick: () -> Unit) {
    val active = aspect != CropAspect.FREE
    ScrimPill(onClickLabel = "Next crop shape", onClick = onClick, selected = active) {
        Icon(
            imageVector = Icons.Default.Crop,
            contentDescription = "Crop shape",
            tint = if (active) Color.White else FsText,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = aspect.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) Color.White else FsText,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

/** A 36 dp scrim pill inside a 48 dp touch target, orange when [selected]. */
@Composable
private fun ScrimPill(
    onClickLabel: String,
    onClick: () -> Unit,
    selected: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClickLabel = onClickLabel, onClick = onClick, role = Role.Button)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(36.dp)
                .defaultMinSize(minWidth = 36.dp)
                .background(
                    color = if (selected) FireOrange else Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * A 36 dp translucent circle drawn inside a 48 dp touch target, so the
 * smaller visual still gets a finger-sized hit area — the one control both
 * the fullscreen viewer and the send preview's rail are built from. Dimmed and
 * unclickable when [onClick] is null.
 */
@Composable
internal fun ScrimCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = onClick != null, onClick = onClick ?: {}, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(color = Color.Black.copy(alpha = 0.5f), shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (onClick != null) Color.White else FsTextMute,
                modifier = Modifier.size(20.dp),
            )
        }
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
