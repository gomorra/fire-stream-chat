package com.firestream.chat.ui.chat.imageedit

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.util.ImageOverlay
import com.firestream.chat.domain.util.OverlayContent
import com.firestream.chat.domain.util.OverlayGeometry
import com.firestream.chat.ui.chat.picker.EmojiTab
import com.firestream.chat.ui.chat.picker.PickerPanel
import com.firestream.chat.ui.chat.picker.PickerSelection
import com.firestream.chat.ui.chat.picker.PickerTab
import com.firestream.chat.ui.chat.picker.ShapeTab
import com.firestream.chat.ui.chat.picker.StickerTab
import com.firestream.chat.ui.chat.picker.TextTab
import com.firestream.chat.ui.theme.FireOrange
import com.firestream.chat.ui.theme.FsText
import com.firestream.chat.ui.theme.FsTextMute
import kotlinx.coroutines.launch

/**
 * What the overlay screen's chrome can fire, in one immutable bundle.
 *
 * Select, place, move, scale, rotate, delete, undo, redo, layer, cancel and done
 * is eleven callbacks before a single piece of state, and this repo's Composable
 * ceiling is enforced by ART as a **`VerifyError` on first render, not at
 * compile time** — Robolectric runs on the JVM and would let every test below
 * pass while the editor crashed on a real phone. Same shape and same reason as
 * `MessageBubbleCallbacks`, [AdjustCallbacks] and [DrawCallbacks].
 *
 * This screen is the one the plan singles out as most likely to hit the ceiling
 * (selection state, two drag handles, a picker panel and four tabs), so it is
 * also split into small composables: the ceiling counts a *method's* registers,
 * and a bundle alone would not save a single 400-line one.
 */
@Immutable
internal data class OverlayCallbacks(
    /** A tap: the index under the finger, or null for a tap on bare photo. */
    val onSelect: (Int?) -> Unit,
    val onPlace: (OverlayContent) -> Unit,
    /** A drag on the selected object: where its centre goes, in fractions of the image. */
    val onMove: (centerX: Float, centerY: Float) -> Unit,
    val onScale: (Float) -> Unit,
    val onRotate: (Float) -> Unit,
    val onDelete: () -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onToggleLayer: () -> Unit,
    val onCancel: () -> Unit,
    val onDone: () -> Unit,
)

/**
 * Emoji, stickers, text and shapes placed on one image, as a full-screen overlay
 * on the send preview.
 *
 * ### One screen for four kinds of thing
 *
 * Drag, scale, rotate, z-order and delete are the same machinery whichever of
 * them is selected (`.claude/plans/image-editor.md` §3 Phase 5); only the
 * painting differs. Four screens would have been that machinery written four
 * times, and four chances for a sticker and a text run to behave differently
 * under the same finger.
 *
 * ### What Done writes
 *
 * One [OverlayStack] flattened **once**, on Done, into a single
 * `RasterOp.Overlays` and a single new JPEG that becomes one entry in the
 * preview's edit history (§2.1). Cancel writes nothing, and Done on a photo
 * nothing was placed on cancels rather than burning a history step, a cache file
 * and a generation of JPEG quality on a re-encoded copy — the same rule the
 * adjust and draw screens follow.
 *
 * ### Two handles, not one corner
 *
 * Bottom-right scales, top-right rotates, each with its own live readout. A
 * combined corner is fine while everything on the canvas is an emoji, where
 * neither exact size nor exact angle matters, and stops being fine the moment a
 * shape is in: a box drawn round something is usually wanted axis-aligned or at
 * a deliberate angle, and a combined handle cannot rotate without also resizing.
 * Rotation snaps to 15° and harder to the cardinals, which is only meaningful
 * with a dedicated rotate gesture.
 *
 * ### The eye is a view control
 *
 * Same contract as the draw screen (§2.7): hiding the layer shows the photo
 * underneath so you can judge what an object is covering, and it **never changes
 * what gets written** — Done with the layer hidden still flattens everything,
 * and re-entering always comes back visible. Placing and manipulating are
 * suspended while it is hidden, because an object that landed invisibly would be
 * worse feedback than none.
 */
@Composable
internal fun OverlayImageScreen(
    source: Uri,
    onDone: (Uri) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    services: ImageEditServices = ImageEditServices(),
    recentEmojis: List<String> = emptyList(),
    onEmojiUsed: (String) -> Unit = {},
    /**
     * Every batch item's current step, read at the moment Done flattens rather
     * than captured when the editor opened — see [AdjustImageScreen] for why a
     * forgotten set silently deletes an edit on another page.
     */
    liveSteps: () -> Set<Uri> = { emptySet() },
) {
    val scope = rememberCoroutineScope()

    var stack by rememberSaveable(source, stateSaver = OverlayStack.StackSaver) {
        mutableStateOf(OverlayStack())
    }
    // -1 rather than a nullable Int so the saver stays a primitive.
    var selectedIndex by rememberSaveable(source) { mutableIntStateOf(NOTHING_SELECTED) }
    var layerVisible by rememberSaveable(source) { mutableStateOf(true) }
    var colorArgb by rememberSaveable(source) { mutableLongStateOf(EDIT_COLORS.first()) }
    var filled by rememberSaveable(source) { mutableStateOf(false) }
    var draftText by rememberSaveable(source) { mutableStateOf("") }

    var preview by remember(source) { mutableStateOf<Bitmap?>(null) }
    var flattening by remember(source) { mutableStateOf(false) }
    var failed by remember(source) { mutableStateOf(false) }

    LaunchedEffect(source, services) {
        preview = services.renderPreview(source, emptyList(), PREVIEW_MAX_PX)
    }

    // The selection can be left dangling by an undo that removed the object it
    // pointed at, and an index that outruns the list would select the wrong
    // thing — or crash a delete. Re-checked against the list rather than
    // maintained alongside it.
    val selected = selectedIndex.takeIf { it in stack.overlays.indices }

    val callbacks = OverlayCallbacks(
        onSelect = { selectedIndex = it ?: NOTHING_SELECTED },
        onPlace = { content ->
            failed = false
            val placed = stack.place(
                ImageOverlay(content = content, centerX = 0.5f, centerY = 0.5f),
            )
            // At the cap `place` returns the stack unchanged, and the standing
            // notice below already says why — no separate "your tap bounced"
            // flag, which is state that has to be cleared on undo and redo too
            // and reads as a lie the moment it is not.
            if (placed !== stack) {
                stack = placed
                // Newly placed is newly selected, so the handles are on the
                // thing you just added without a second tap to find it.
                selectedIndex = placed.overlays.lastIndex
            }
        },
        onMove = { x, y ->
            selected?.let { index -> stack = stack.adjust(index) { OverlayGeometry.movedTo(it, x, y) } }
        },
        onScale = { scale ->
            selected?.let { index -> stack = stack.adjust(index) { it.copy(scale = scale) } }
        },
        onRotate = { degrees ->
            selected?.let { index -> stack = stack.adjust(index) { it.copy(rotationDegrees = degrees) } }
        },
        onDelete = {
            selected?.let { index ->
                stack = stack.delete(index)
                selectedIndex = NOTHING_SELECTED
            }
        },
        onUndo = {
            stack = stack.undo()
            selectedIndex = NOTHING_SELECTED
        },
        onRedo = {
            stack = stack.redo()
            selectedIndex = NOTHING_SELECTED
        },
        onToggleLayer = { layerVisible = !layerVisible },
        onCancel = onCancel,
        onDone = {
            val op = stack.toOp()
            if (op == null) {
                onCancel()
            } else {
                flattening = true
                failed = false
                scope.launch {
                    // `stack`, not what is on screen: the layer-eye is a view
                    // control and hiding it must not quietly drop the objects.
                    val result = services.rasterize(source, listOf(op), liveSteps())
                    flattening = false
                    if (result == null) failed = true else onDone(result)
                }
            }
        },
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        OverlayCanvas(
            preview = preview,
            overlays = if (layerVisible) stack.overlays else emptyList(),
            selected = if (layerVisible) selected else null,
            enabled = layerVisible && !flattening,
            callbacks = callbacks,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = EditorChrome.TOP_BAR_HEIGHT_DP.dp, bottom = PICKER_HEIGHT_DP.dp),
        )

        EditTopBar(
            labels = OverlayTopBarLabels,
            canUndo = stack.canUndo,
            canRedo = stack.canRedo,
            enabled = !flattening,
            onCancel = callbacks.onCancel,
            onUndo = callbacks.onUndo,
            onRedo = callbacks.onRedo,
            onDone = callbacks.onDone,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars),
            middleContent = {
                LayerEyeButton(
                    visible = layerVisible,
                    enabled = !flattening,
                    onClick = callbacks.onToggleLayer,
                )
            },
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // The keyboard's inset as well as the navigation bar's: the text
                // tab's field, its Add button and the emoji search all live in this
                // panel, and the keyboard used to cover every one of them. Their
                // union, not `imePadding()` on top — that would count the bar twice.
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) {
            EditFailureBanner(visible = failed, message = "Couldn't apply what you placed. Try again.")
            // Derived, not tracked: the notice stands while the photo is full
            // and goes as soon as anything is deleted or undone.
            EditFailureBanner(
                visible = stack.isFull,
                message = "That's as many as one photo can hold.",
            )
            OverlayPicker(
                draft = draftText,
                colorArgb = colorArgb,
                filled = filled,
                recentEmojis = recentEmojis,
                hasSelection = selected != null && layerVisible,
                callbacks = callbacks,
                style = OverlayStyleCallbacks(
                    onDraft = { draftText = it },
                    onColor = { colorArgb = it },
                    onFilled = { filled = it },
                    onEmojiUsed = onEmojiUsed,
                ),
                modifier = Modifier.fillMaxWidth().height(PICKER_HEIGHT_DP.dp),
            )
        }

        if (flattening) EditFlattenScrim()
    }
}

/** The three style writes the picker's tabs need, bundled for the ceiling's sake. */
@Immutable
internal data class OverlayStyleCallbacks(
    val onDraft: (String) -> Unit,
    val onColor: (Long) -> Unit,
    val onFilled: (Boolean) -> Unit,
    val onEmojiUsed: (String) -> Unit,
)

/**
 * The picker, with the four tabs only this host declares.
 *
 * The tab dispatch is here rather than inside `PickerPanel` deliberately: the
 * shell owns the chrome and implements no content, so each tab's own state — a
 * draft line, a colour, a fill — stays with the host that has it instead of
 * accumulating as parameters on a shell three other screens also use.
 *
 * The delete button is `null` while nothing is selected, which is what makes it
 * **hidden rather than greyed**: a control that is always there and usually dead
 * teaches people to ignore it.
 */
@Composable
private fun OverlayPicker(
    draft: String,
    colorArgb: Long,
    filled: Boolean,
    recentEmojis: List<String>,
    hasSelection: Boolean,
    callbacks: OverlayCallbacks,
    style: OverlayStyleCallbacks,
    modifier: Modifier = Modifier,
) {
    val onSelection: (PickerSelection) -> Unit = { selection ->
        when (selection) {
            is PickerSelection.Emoji -> {
                callbacks.onPlace(OverlayContent.Emoji(selection.emoji))
                style.onEmojiUsed(selection.emoji)
            }

            is PickerSelection.Overlay -> callbacks.onPlace(selection.content)
        }
    }

    PickerPanel(
        tabs = EDITOR_TABS,
        modifier = modifier,
        onDelete = if (hasSelection) callbacks.onDelete else null,
    ) { tab, query ->
        when (tab) {
            PickerTab.EMOJI -> EmojiTab(
                query = query,
                recentEmojis = recentEmojis,
                onSelection = onSelection,
            )

            PickerTab.STICKER -> StickerTab(query = query, onSelection = onSelection)

            PickerTab.TEXT -> TextTab(
                draft = draft,
                colorArgb = colorArgb,
                filled = filled,
                onDraft = style.onDraft,
                onColor = style.onColor,
                onFilled = style.onFilled,
                onSelection = onSelection,
            )

            PickerTab.SHAPE -> ShapeTab(
                colorArgb = colorArgb,
                filled = filled,
                onColor = style.onColor,
                onFilled = style.onFilled,
                onSelection = onSelection,
            )

            // Enumerated and declared by nobody — see PickerTab.GIF. Named
            // rather than swept into an `else`, so a tab added to the enum
            // fails to compile here instead of quietly rendering shapes.
            PickerTab.GIF -> Unit
        }
    }
}

/** The layer eye, in the top bar's middle slot — with the actions it is not, beside them. */
@Composable
private fun LayerEyeButton(visible: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(
            imageVector = if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            contentDescription = if (visible) "Hide what you placed" else "Show what you placed",
            tint = when {
                !enabled -> FsTextMute
                visible -> FsText
                else -> FireOrange
            },
            modifier = Modifier.size(22.dp),
        )
    }
}

/** A spinner until the photo lands; the canvas has nothing to place things on before then. */
@Composable
internal fun OverlayPreviewPlaceholder() {
    CircularProgressIndicator(color = FsTextMute)
}

private val OverlayTopBarLabels = EditTopBarLabels(
    cancel = "Cancel overlays",
    undo = "Undo placement",
    redo = "Redo placement",
    done = "Apply overlays",
)

/**
 * The tabs this host declares (§2.8). Emoji and stickers because they are
 * pictures to place, text and shapes because they are the annotation half of the
 * same job — and **not GIF**, which is impossible rather than unbuilt: the
 * pipeline ends at JPEG, and a flattened animation is one frame and a worse
 * sticker.
 */
private val EDITOR_TABS = listOf(PickerTab.EMOJI, PickerTab.STICKER, PickerTab.TEXT, PickerTab.SHAPE)

/** No selection, as an index — see [OverlayImageScreen]'s `selectedIndex`. */
internal const val NOTHING_SELECTED = -1

/**
 * Long-edge ceiling for the preview decode, in pixels rather than dp: this is a
 * decode target and should not change with display density the way a layout
 * does. Same value and same reason as the draw screen's.
 */
private const val PREVIEW_MAX_PX = 1600

/**
 * How much of the screen the picker takes.
 *
 * Taller than the draw screen's tool panel because this one holds a scrolling
 * grid rather than a row of buttons, and short enough that a phone-shaped photo
 * still has most of the screen to be placed on.
 */
private const val PICKER_HEIGHT_DP = 300
