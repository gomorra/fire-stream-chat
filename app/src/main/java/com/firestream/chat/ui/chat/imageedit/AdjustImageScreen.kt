package com.firestream.chat.ui.chat.imageedit

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.util.ImageEditGeometry
import com.firestream.chat.domain.util.RasterOp
import com.firestream.chat.domain.util.SourceImage
import com.firestream.chat.ui.theme.FireOrange
import com.firestream.chat.ui.theme.FsText
import com.firestream.chat.ui.theme.FsTextDim
import com.firestream.chat.ui.theme.FsTextMute
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every action the adjust screen's chrome can fire, in one immutable bundle.
 *
 * Rotate, flip, straighten, crop, aspect, resize, undo, redo, reset, cancel and
 * done is eleven callbacks before a single piece of state is passed, and this
 * repo's Composable parameter ceiling (~15) is enforced by ART with a
 * **`VerifyError` on first render, not at compile time** — it has already cost
 * one chat-open crash (`docs/GOTCHAS.md`). Robolectric runs on the JVM and would
 * not reproduce it, so the tests below this would keep passing while the editor
 * crashed on a real device. Same shape and same reason as
 * `MessageBubbleCallbacks`.
 */
@Immutable
internal data class AdjustCallbacks(
    val onRotate: () -> Unit,
    val onFlip: () -> Unit,
    val onSelectTool: (AdjustTool) -> Unit,
    /** Live, per slider frame — nothing is committed until the finger lifts. */
    val onStraighten: (Float) -> Unit,
    val onStraightenSettled: () -> Unit,
    val onCrop: (CropRect) -> Unit,
    val onAspect: (CropAspect) -> Unit,
    /** Null is the "Original" preset, which removes the resize rather than adding one. */
    val onResize: (Int?) -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onReset: () -> Unit,
    val onCancel: () -> Unit,
    val onDone: () -> Unit,
)

/**
 * Rotate, flip, straighten, crop and resize for one image, as a full-screen
 * overlay over the send preview.
 *
 * ### What Done writes
 *
 * The screen builds an [AdjustStack] of [RasterOp]s and flattens it **once**, on
 * Done, into a single new JPEG that becomes one entry in the preview's edit
 * history (`.claude/plans/image-editor.md` §2.1). Cancel writes nothing at all.
 * Nothing here is a NavHost route: `pendingMedia` is state local to `ChatScreen`
 * and making the editors routes would force the whole batch through a
 * `SavedStateHandle` round-trip for no user-visible gain (§2.4).
 *
 * ### The three coordinate spaces, and the one helper between them
 *
 * The photo is displayed axis-aligned under a uniform fit, so [ImageFitMapper]
 * is the whole of the mapping: it turns a finger position into a fraction of the
 * image and back into the place a crop handle should be drawn. The crop frame is
 * stored **normalized to the image**, never in canvas pixels, so it survives a
 * device rotation and a screen-size change without drifting (§2.3).
 *
 * ### What the preview bitmap does and does not contain
 *
 * The bitmap comes from [ImageEditServices.renderPreview], which runs the *same*
 * decode and the same op application the rasterizer will — so what is on screen
 * is what Done writes, scaled. Re-rendering it costs a decode, so it happens only
 * on discrete commits, and the two continuous gestures are applied on top of the
 * last render instead: a straighten as a live rotation and upscale, a crop as an
 * overlay. [previewOps] is the seam, and its KDoc says which ops it withholds.
 *
 * ### Straighten crops as you drag
 *
 * The image scales up as the angle changes so it never stops being a full
 * rectangle — Google Photos, Snapseed and iOS Photos all do this, and the
 * alternative (expand the frame, show black corners, offer an explicit auto-crop
 * button) has a failure mode this cannot have: straighten, miss the button, send
 * a photo with black triangles in it. Decided 2026-09-09; if the trade is ever
 * exposed it is a fit ⇄ fill toggle at the right end of the slider row, never a
 * button floating over the photo.
 *
 * ### No layer-visibility toggle
 *
 * The other two editor screens get an eye button; this one deliberately does
 * not. There is no added layer here to hide — only the photo — and a control
 * that did nothing on this screen would teach people to distrust it on the two
 * where it works (§2.7).
 */
@Composable
internal fun AdjustImageScreen(
    source: Uri,
    onDone: (Uri) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    isHd: Boolean = false,
    services: ImageEditServices = ImageEditServices(),
    /**
     * Every batch item's current step, read at the moment Done flattens rather
     * than captured when the editor opened.
     *
     * The rasterizer's byte budget evicts globally oldest-first, and a page the
     * user is *not* looking at owns some of the oldest files in the cache while
     * its newest one is still the image that page shows. Without this, flattening
     * a crop on page 1 can delete the crop already made on page 3
     * (`.claude/plans/image-editor.md` §3, Phase 2 departure 7).
     */
    liveSteps: () -> Set<Uri> = { emptySet() },
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var stack by rememberSaveable(source, stateSaver = AdjustStack.StackSaver) {
        mutableStateOf(AdjustStack())
    }
    var tool by rememberSaveable(source) { mutableStateOf(AdjustTool.NONE) }
    var aspect by rememberSaveable(source) { mutableStateOf(CropAspect.FREE) }
    // Saved, not merely remembered, for the reason the whole stack is: turning
    // the phone mid-crop must not throw the frame away, and the frame is
    // normalized to the image so it means the same thing in either orientation.
    var cropRect by rememberSaveable(source, stateSaver = CropRect.Saver) {
        mutableStateOf(CropRect.Full)
    }
    var angle by rememberSaveable(source) { mutableFloatStateOf(0f) }

    var preview by remember(source) { mutableStateOf<Bitmap?>(null) }
    var sourceImage by remember(source) { mutableStateOf<SourceImage?>(null) }
    var flattening by remember(source) { mutableStateOf(false) }
    var failed by remember(source) { mutableStateOf(false) }

    // The screen's longest edge: a preview only ever has to look right, and
    // decoding past what the display resolves costs milliseconds per commit for
    // pixels nobody sees.
    val previewCeiling = remember(density) {
        with(density) { PREVIEW_MAX_DP.dp.roundToPx() }.coerceAtLeast(1)
    }

    val activeOps = stack.active
    val rendered = remember(activeOps, tool) { previewOps(activeOps, tool) }

    LaunchedEffect(source, rendered) {
        preview = services.renderPreview(source, rendered, previewCeiling)
    }
    LaunchedEffect(source) {
        sourceImage = services.probeSource(source)
    }
    // The slider follows the stack whenever the stack moves under it — entering
    // the tool on an image that is already straightened, and undo or redo while
    // the tool is open. Not while dragging: a commit replaces the trailing op in
    // place, so this resolves to the value already on screen.
    LaunchedEffect(tool, stack) {
        if (tool == AdjustTool.STRAIGHTEN) angle = stack.trailingAngle()
    }

    /** Folds whichever tool is mid-edit into the stack, so Done cannot drop it. */
    fun commitPending(current: AdjustStack): AdjustStack = when (tool) {
        AdjustTool.CROP -> cropRect.toOp()?.let(current::push) ?: current
        else -> current
    }

    fun selectTool(next: AdjustTool) {
        stack = commitPending(stack)
        cropRect = CropRect.Full
        aspect = CropAspect.FREE
        tool = if (tool == next) AdjustTool.NONE else next
    }

    val callbacks = AdjustCallbacks(
        onRotate = {
            stack = commitPending(stack).push(RasterOp.Rotate(QUARTER_TURN))
            if (tool == AdjustTool.CROP) cropRect = CropRect.Full
        },
        onFlip = {
            stack = commitPending(stack).push(RasterOp.Flip(horizontal = true))
            if (tool == AdjustTool.CROP) cropRect = CropRect.Full
        },
        onSelectTool = ::selectTool,
        onStraighten = { angle = it },
        onStraightenSettled = {
            val settled = angle.coerceIn(
                -ImageEditGeometry.STRAIGHTEN_LIMIT,
                ImageEditGeometry.STRAIGHTEN_LIMIT,
            )
            stack = stack.collapse(
                op = if (abs(settled) < ANGLE_EPSILON) null else RasterOp.Straighten(settled),
                sameKind = { it is RasterOp.Straighten },
            )
        },
        onCrop = { cropRect = it },
        onAspect = { chosen ->
            aspect = chosen
            val bitmap = preview
            cropRect = if (bitmap == null) {
                CropRect.Full
            } else {
                CropGeometry.centered(chosen, bitmap.width, bitmap.height)
            }
        },
        onResize = { longEdge ->
            stack = stack.collapse(
                op = longEdge?.let(RasterOp::Resize),
                sameKind = { it is RasterOp.Resize },
            )
        },
        onUndo = { stack = stack.undo().also { cropRect = CropRect.Full } },
        onRedo = { stack = stack.redo().also { cropRect = CropRect.Full } },
        onReset = {
            stack = stack.reset()
            cropRect = CropRect.Full
            aspect = CropAspect.FREE
            angle = 0f
        },
        onCancel = onCancel,
        onDone = {
            val committed = commitPending(stack)
            stack = committed
            if (committed.isPristine) {
                // Nothing was changed, so there is nothing to flatten. Writing a
                // re-encoded copy of the photo anyway would burn a history step,
                // a cache file and a generation of JPEG quality on a no-op.
                onCancel()
            } else {
                flattening = true
                failed = false
                scope.launch {
                    val result = services.rasterize(source, committed.active, liveSteps())
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
        AdjustPhoto(
            preview = preview,
            tool = tool,
            angle = angle,
            cropRect = cropRect,
            aspect = aspect,
            onCrop = callbacks.onCrop,
            modifier = Modifier
                .fillMaxSize()
                // The bars' insets as well as their heights: the top bar and the
                // bottom panel are each pushed in by a system bar, and a canvas
                // that knew only their heights ran a status bar's depth under the
                // one and a navigation bar's under the other.
                .windowInsetsPadding(
                    WindowInsets.statusBars.only(WindowInsetsSides.Top)
                        .union(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
                )
                .padding(top = EditorChrome.TOP_BAR_HEIGHT_DP.dp, bottom = BOTTOM_PANEL_HEIGHT_DP.dp),
        )

        AdjustTopBar(
            canUndo = stack.canUndo,
            canRedo = stack.canRedo,
            canReset = stack.ops.isNotEmpty(),
            enabled = !flattening,
            callbacks = callbacks,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .background(Color.Black.copy(alpha = 0.75f)),
        ) {
            EditFailureBanner(visible = failed, message = "Couldn't apply the edit. Try again.")

            AdjustToolPanel(
                tool = tool,
                angle = angle,
                aspect = aspect,
                source = sourceImage,
                ops = activeOps,
                longEdge = stack.trailingLongEdge(),
                isHd = isHd,
                callbacks = callbacks,
            )

            AdjustToolRow(tool = tool, enabled = !flattening, callbacks = callbacks)
        }

        if (flattening) EditFlattenScrim()
    }
}

/**
 * The photo, the live straighten, and the crop overlay.
 *
 * The gesture is attached to the **whole canvas**, not to the fitted image, so a
 * corner drag that runs past the edge of the photo keeps tracking the finger
 * instead of stopping dead at the letterbox — and so every screen coordinate
 * goes through [ImageFitMapper] rather than being divided by a width that
 * happens to be the image's.
 *
 * ### The crop margin
 *
 * With the crop tool open the photo is fitted inside a margin instead of edge to
 * edge, so a grip on the photo's edge is not flush with the edge of the glass
 * where the back gesture takes the touch — [CropGeometry.reachableInsets] says
 * how wide, and why it is only part of full clearance. Only the
 * *fit* moves in: the canvas, and the gesture layer on it, stay full-size,
 * because a margin the overlay did not cover would be exactly the strip around
 * each corner that a finger reaching for it lands in. The frame is normalized to
 * the image, so the photo changing size underneath it costs nothing.
 */
@Composable
private fun AdjustPhoto(
    preview: Bitmap?,
    tool: AdjustTool,
    angle: Float,
    cropRect: CropRect,
    aspect: CropAspect,
    onCrop: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    // How far each canvas edge is from the window edge — the edge the gesture
    // insets are measured from.
    var canvasEdges by remember { mutableStateOf(EdgeInsetsPx.Zero) }

    // Cutouts and a landscape navigation bar count too: a handle under either
    // is no more reachable than one under a gesture strip.
    val blocked = WindowInsets.systemGestures.union(WindowInsets.safeDrawing)
    val marginFraction by animateFloatAsState(
        targetValue = if (tool == AdjustTool.CROP) 1f else 0f,
        label = "crop margin",
    )
    val margin = CropGeometry.reachableInsets(
        canvas = canvasEdges,
        gestures = EdgeInsetsPx(
            left = blocked.getLeft(density, layoutDirection).toFloat(),
            top = blocked.getTop(density).toFloat(),
            right = blocked.getRight(density, layoutDirection).toFloat(),
            bottom = blocked.getBottom(density).toFloat(),
        ),
        grabRadius = with(density) { HANDLE_GRAB_DP.dp.toPx() },
    ).scaled(marginFraction)

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            canvas = coordinates.size
            val bounds = coordinates.boundsInRoot()
            val root = coordinates.findRootCoordinates().size
            canvasEdges = EdgeInsetsPx(
                left = bounds.left,
                top = bounds.top,
                right = root.width - bounds.right,
                bottom = root.height - bounds.bottom,
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = preview
        if (bitmap == null) {
            CircularProgressIndicator(color = FsTextMute)
            return@Box
        }

        val mapper = ImageFitMapper(
            canvasWidth = canvas.width - margin.left - margin.right,
            canvasHeight = canvas.height - margin.top - margin.bottom,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            originX = margin.left,
            originY = margin.top,
        )
        if (mapper.scale <= 0f) return@Box

        val straightening = tool == AdjustTool.STRAIGHTEN
        // Scaling by the reciprocal of what the straighten keeps is exactly the
        // live auto-crop: the visible rectangle stays full because the image
        // grows to cover the corners the rotation would have emptied.
        val liveScale = if (!straightening) {
            1f
        } else {
            1f / ImageEditGeometry.straightenScale(bitmap.width, bitmap.height, angle)
                .coerceAtLeast(MIN_STRAIGHTEN_SCALE)
        }

        Box(
            modifier = Modifier
                // Anchored at the origin, not centred: [mapper] already *is* the
                // centring, and letting the Box centre it as well would offset the
                // photo by half the letterbox while the crop overlay below kept
                // drawing at the mapper's coordinates — handles a finger-width away
                // from the photo they belong to. Every layer here places itself
                // through the mapper or through none of it.
                .align(Alignment.TopStart)
                .offset { IntOffset(mapper.offsetX.roundToInt(), mapper.offsetY.roundToInt()) }
                .size(
                    width = with(density) { mapper.fittedWidth.toDp() },
                    height = with(density) { mapper.fittedHeight.toDp() },
                )
                // Outside the rotation on purpose: the clip is the frame the
                // photo is being straightened *inside*, which is what makes the
                // corners stay full rather than swinging into view.
                .clipToBounds(),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Image being adjusted",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = if (straightening) angle else 0f
                        scaleX = liveScale
                        scaleY = liveScale
                    },
            )
        }

        if (straightening) StraightenGrid(mapper)

        if (tool == AdjustTool.CROP) {
            CropOverlay(
                mapper = mapper,
                rect = cropRect,
                aspect = aspect,
                onCrop = onCrop,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
            )
        }
    }
}

/**
 * A faint thirds grid while straightening — the reference the eye actually uses
 * to decide whether a horizon is level. Drawn over the fitted rect, so it stays
 * put while the photo turns underneath it.
 */
@Composable
private fun StraightenGrid(mapper: ImageFitMapper) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        thirdsGrid(
            left = mapper.offsetX,
            top = mapper.offsetY,
            width = mapper.fittedWidth,
            height = mapper.fittedHeight,
            alpha = 0.22f,
        )
    }
}

/**
 * The thirds lines inside a rectangle, drawn the same way for the straighten
 * tool and the crop frame — the two places in this screen that show one, and
 * two places that must not drift apart in weight or spacing when only one of
 * them is edited.
 */
private fun DrawScope.thirdsGrid(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    alpha: Float,
) {
    val line = Color.White.copy(alpha = alpha)
    for (index in 1 until GRID_DIVISIONS) {
        val fraction = index.toFloat() / GRID_DIVISIONS
        val x = left + width * fraction
        val y = top + height * fraction
        drawLine(line, Offset(x, top), Offset(x, top + height), strokeWidth = 1f)
        drawLine(line, Offset(left, y), Offset(left + width, y), strokeWidth = 1f)
    }
}

/** The scrim, the thirds grid, the corner brackets and side bars, and the drag that moves them. */
@Composable
private fun CropOverlay(
    mapper: ImageFitMapper,
    rect: CropRect,
    aspect: CropAspect,
    onCrop: (CropRect) -> Unit,
    imageWidth: Int,
    imageHeight: Int,
) {
    val density = LocalDensity.current
    val grabPx = with(density) { HANDLE_GRAB_DP.dp.toPx() }
    val reachPx = with(density) { HANDLE_REACH_DP.dp.toPx() }
    val armPx = with(density) { HANDLE_ARM_DP.dp.toPx() }
    val strokePx = with(density) { HANDLE_STROKE_DP.dp.toPx() }

    // A fixed dp grab radius is a different fraction of a tall photo than of a
    // wide one, so it is converted per axis rather than shared.
    val toleranceX = if (mapper.fittedWidth > 0f) grabPx / mapper.fittedWidth else 1f
    val toleranceY = if (mapper.fittedHeight > 0f) grabPx / mapper.fittedHeight else 1f
    val reachX = if (mapper.fittedWidth > 0f) reachPx / mapper.fittedWidth else 1f
    val reachY = if (mapper.fittedHeight > 0f) reachPx / mapper.fittedHeight else 1f

    var handle by remember { mutableStateOf<CropHandle?>(null) }
    var moving by remember { mutableStateOf(false) }
    // Where the finger went down, recorded before the drag detector sees the
    // gesture. `detectDragGestures` calls back only once touch slop is crossed;
    // recording the down here means the hit test and the grab gap are measured
    // from where the finger actually landed, whatever position that later
    // callback reports.
    var downPosition by remember { mutableStateOf(Offset.Zero) }
    // The finger's distance from the grip it took, in normalized units, kept for
    // the whole drag: a grip grabbed from inside the frame follows the finger at
    // that gap instead of jumping under it — the only reason its grab area can
    // reach inward, away from the back-gesture strip, at all.
    var grabOffset by remember { mutableStateOf(FitPoint(0f, 0f)) }

    // The frame as it is *now*, not as it was when the gesture coroutine
    // started. `pointerInput` restarts only when one of its keys changes, and
    // the frame deliberately is not one of them: adding it would tear down the
    // detector mid-drag, on the very state change the drag itself is producing.
    // So the lambdas below would otherwise go on reading the frame they were
    // created with, and every pointer event would recompute from a frame one
    // whole gesture out of date.
    //
    // The regression that came of that is worth naming, because neither half of
    // it looks like a stale read: after one corner drag the detector still
    // believed the frame filled the photo, so the next grab looked for corners
    // at 0,0 and 1,1, missed them, fell through to move-mode — and `move` on a
    // full-image frame clamps to zero travel, snapping the crop back. The frame
    // could be dragged exactly once and never moved bodily at all.
    val currentRect by rememberUpdatedState(rect)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Crop frame" }
            .pointerInput(Unit) {
                // Observes only — nothing is consumed, so the drag detector
                // below still sees every event.
                awaitEachGesture {
                    downPosition = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    ).position
                }
            }
            .pointerInput(mapper, aspect, imageWidth, imageHeight) {
                detectDragGestures(
                    onDragStart = {
                        val point = mapper.screenToNormalized(FitPoint(downPosition.x, downPosition.y))
                        val grabbed = CropGeometry.handleAt(
                            currentRect, point.x, point.y, toleranceX, toleranceY, reachX, reachY,
                        )
                        handle = grabbed
                        grabOffset = if (grabbed == null) {
                            FitPoint(0f, 0f)
                        } else {
                            val grip = CropGeometry.gripPoint(currentRect, grabbed)
                            FitPoint(point.x - grip.x, point.y - grip.y)
                        }
                        moving = grabbed == null &&
                            CropGeometry.contains(currentRect, point.x, point.y)
                    },
                    onDragEnd = { handle = null; moving = false },
                    onDragCancel = { handle = null; moving = false },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val grabbed = handle
                        if (grabbed != null) {
                            // Absolute, less the gap the grip was grabbed at: it
                            // tracks the finger exactly, slop included, and a
                            // finger that runs past the photo and comes back
                            // finds the grip where it left it.
                            val point = mapper.screenToNormalized(
                                FitPoint(change.position.x, change.position.y),
                            )
                            onCrop(
                                CropGeometry.drag(
                                    rect = currentRect,
                                    handle = grabbed,
                                    x = point.x - grabOffset.x,
                                    y = point.y - grabOffset.y,
                                    aspect = aspect,
                                    imageWidth = imageWidth,
                                    imageHeight = imageHeight,
                                ),
                            )
                        } else if (moving) {
                            val dx = if (mapper.fittedWidth > 0f) dragAmount.x / mapper.fittedWidth else 0f
                            val dy = if (mapper.fittedHeight > 0f) dragAmount.y / mapper.fittedHeight else 0f
                            onCrop(CropGeometry.move(currentRect, dx, dy))
                        }
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val topLeft = mapper.normalizedToScreen(FitPoint(rect.left, rect.top))
            val bottomRight = mapper.normalizedToScreen(FitPoint(rect.right, rect.bottom))
            val left = topLeft.x
            val top = topLeft.y
            val right = bottomRight.x
            val bottom = bottomRight.y
            val frameWidth = (right - left).coerceAtLeast(0f)
            val frameHeight = (bottom - top).coerceAtLeast(0f)

            val scrim = Color.Black.copy(alpha = 0.55f)
            drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(size.width, top.coerceAtLeast(0f)))
            drawRect(
                color = scrim,
                topLeft = Offset(0f, bottom),
                size = Size(size.width, (size.height - bottom).coerceAtLeast(0f)),
            )
            drawRect(scrim, topLeft = Offset(0f, top), size = Size(left.coerceAtLeast(0f), frameHeight))
            drawRect(
                color = scrim,
                topLeft = Offset(right, top),
                size = Size((size.width - right).coerceAtLeast(0f), frameHeight),
            )

            thirdsGrid(left = left, top = top, width = frameWidth, height = frameHeight, alpha = 0.25f)

            drawRect(
                color = Color.White.copy(alpha = 0.85f),
                topLeft = Offset(left, top),
                size = Size(frameWidth, frameHeight),
                style = Stroke(width = 1f),
            )

            // L-shaped brackets rather than dots: a bracket says which two edges
            // a corner owns, and it stays visible against both a bright sky and
            // a dark foreground because it sits on the frame line.
            val arms = listOf(
                Triple(left, top, 1f to 1f),
                Triple(right, top, -1f to 1f),
                Triple(left, bottom, 1f to -1f),
                Triple(right, bottom, -1f to -1f),
            )
            for ((x, y, direction) in arms) {
                val (dirX, dirY) = direction
                drawLine(
                    color = Color.White,
                    start = Offset(x, y),
                    end = Offset(x + armPx * dirX, y),
                    strokeWidth = strokePx,
                )
                drawLine(
                    color = Color.White,
                    start = Offset(x, y),
                    end = Offset(x, y + armPx * dirY),
                    strokeWidth = strokePx,
                )
            }

            // A straight bar at the middle of each side: a side grip owns one
            // edge, so it is drawn along that edge rather than as a bracket, one
            // bracket arm long so the two kinds of grip read as a single set.
            val midX = (left + right) / 2f
            val midY = (top + bottom) / 2f
            val halfBar = armPx / 2f
            val bars = listOf(
                Offset(midX - halfBar, top) to Offset(midX + halfBar, top),
                Offset(midX - halfBar, bottom) to Offset(midX + halfBar, bottom),
                Offset(left, midY - halfBar) to Offset(left, midY + halfBar),
                Offset(right, midY - halfBar) to Offset(right, midY + halfBar),
            )
            for ((start, end) in bars) {
                drawLine(color = Color.White, start = start, end = end, strokeWidth = strokePx)
            }
        }
    }
}

/** Cancel and Done at the ends, the history controls in the middle. */
@Composable
private fun AdjustTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    canReset: Boolean,
    enabled: Boolean,
    callbacks: AdjustCallbacks,
    modifier: Modifier = Modifier,
) {
    EditTopBar(
        labels = AdjustTopBarLabels,
        canUndo = canUndo,
        canRedo = canRedo,
        enabled = enabled,
        onCancel = callbacks.onCancel,
        onUndo = callbacks.onUndo,
        onRedo = callbacks.onRedo,
        onDone = callbacks.onDone,
        modifier = modifier,
    ) {
        // Reset is this screen's alone, which is why it goes in the slot rather
        // than into the shared bar: the draw screen has no all-at-once escape,
        // because undo there already walks one stroke at a time.
        TextButton(
            onClick = callbacks.onReset,
            enabled = enabled && canReset,
            modifier = Modifier.semantics { contentDescription = "Reset adjustments" },
        ) {
            Text(
                text = "Reset",
                style = MaterialTheme.typography.labelLarge,
                color = editTint(enabled = enabled && canReset),
            )
        }
    }
}

private val AdjustTopBarLabels = EditTopBarLabels(
    cancel = "Cancel adjustments",
    undo = "Undo adjustment",
    redo = "Redo adjustment",
    done = "Apply adjustments",
)

/** The contextual panel for whichever tool is open; nothing at all for [AdjustTool.NONE]. */
@Composable
private fun AdjustToolPanel(
    tool: AdjustTool,
    angle: Float,
    aspect: CropAspect,
    source: SourceImage?,
    ops: List<RasterOp>,
    longEdge: Int?,
    isHd: Boolean,
    callbacks: AdjustCallbacks,
) {
    when (tool) {
        AdjustTool.NONE -> Unit

        AdjustTool.STRAIGHTEN -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PANEL_HEIGHT_DP.dp)
                .padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Slider(
                value = angle,
                onValueChange = callbacks.onStraighten,
                onValueChangeFinished = callbacks.onStraightenSettled,
                valueRange = -ImageEditGeometry.STRAIGHTEN_LIMIT..ImageEditGeometry.STRAIGHTEN_LIMIT,
                colors = SliderDefaults.colors(
                    thumbColor = FireOrange,
                    activeTrackColor = FireOrange,
                    inactiveTrackColor = FsTextMute,
                ),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Straighten angle" },
            )
            Text(
                // The readout is the whole feedback loop for a gesture whose
                // result is a fraction of a degree, so it is fixed-width and
                // sits where the finger is not.
                text = "${angle.roundToInt()}°",
                style = MaterialTheme.typography.labelLarge,
                color = FsText,
                modifier = Modifier
                    .width(48.dp)
                    .padding(start = 8.dp),
            )
        }

        AdjustTool.CROP -> ChipRow(
            label = "Aspect presets",
            options = CropAspect.entries.toList(),
            key = { it.name },
            chip = { option ->
                AdjustChip(
                    label = option.label,
                    detail = null,
                    selected = option == aspect,
                    onClick = { callbacks.onAspect(option) },
                )
            },
        )

        AdjustTool.RESIZE -> ChipRow(
            label = "Resize presets",
            options = RESIZE_PRESETS,
            key = { it ?: 0 },
            chip = { preset ->
                AdjustChip(
                    label = preset?.toString() ?: "Original",
                    detail = resizeDetail(source, ops, preset, isHd),
                    selected = preset == longEdge,
                    onClick = { callbacks.onResize(preset) },
                )
            },
        )
    }
}

/**
 * A row of preset chips.
 *
 * **Scrolls**, and that is the right answer here rather than a compromise: five
 * resize presets each carrying their own `W × H · ~size` do not fit a 390 dp
 * row. Unlike the picker's island (§4), a preset row is not a mode switcher —
 * nothing is hidden by scrolling except more of the same kind of choice — so a
 * `LazyRow` costs nothing the alternative (dropping the labels) would not cost
 * more of.
 */
@Composable
private fun <T> ChipRow(
    label: String,
    options: List<T>,
    key: (T) -> Any,
    chip: @Composable (T) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(PANEL_HEIGHT_DP.dp)
            .semantics { contentDescription = label },
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(options, key = key) { option -> chip(option) }
    }
}

/**
 * `2048 × 1536 · ~1.1 MB` for one preset, or null when the source header could
 * not be read.
 *
 * Computed through the *whole* op stack, not from the source alone: a preset
 * offered after a 16:9 crop has to quote the dimensions that crop leaves, and a
 * preset below the image's current long edge is honestly labelled with the size
 * it keeps, because a resize never upscales.
 *
 * These are the dimensions the **edit** writes, and every preset offered here is
 * at or below the long edge a standard-quality send caps at, so they are also
 * the dimensions that get sent — which is what §2.5's "an explicit resize wins"
 * asks for. That constraint is why 2048 is not on the row; see §3, departure 7.
 */
private fun resizeDetail(
    source: SourceImage?,
    ops: List<RasterOp>,
    preset: Int?,
    isHd: Boolean,
): String? {
    if (source == null) return null
    val withPreset = ops.filterNot { it is RasterOp.Resize } + listOfNotNull(preset?.let(RasterOp::Resize))
    val (width, height) = ImageEditGeometry.outputSize(source.width, source.height, withPreset)
    val bytes = ImageEditGeometry.estimatedBytes(width, height, source, isHd)
    val size = formatBytes(bytes)
    return if (size.isEmpty()) "$width × $height" else "$width × $height · ~$size"
}

/** Rotate and flip act at once; straighten, crop and resize open a panel. */
@Composable
private fun AdjustToolRow(
    tool: AdjustTool,
    enabled: Boolean,
    callbacks: AdjustCallbacks,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(EditorChrome.TOOL_ROW_HEIGHT_DP.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditToolButton(Icons.Default.Rotate90DegreesCw, "Rotate", false, enabled, callbacks.onRotate)
        EditToolButton(Icons.Default.Flip, "Flip", false, enabled, callbacks.onFlip)
        EditToolButton(
            icon = Icons.Default.Straighten,
            label = "Straighten",
            selected = tool == AdjustTool.STRAIGHTEN,
            enabled = enabled,
            onClick = { callbacks.onSelectTool(AdjustTool.STRAIGHTEN) },
        )
        EditToolButton(
            icon = Icons.Default.Crop,
            label = "Crop",
            selected = tool == AdjustTool.CROP,
            enabled = enabled,
            onClick = { callbacks.onSelectTool(AdjustTool.CROP) },
        )
        EditToolButton(
            icon = Icons.Default.PhotoSizeSelectLarge,
            label = "Resize",
            selected = tool == AdjustTool.RESIZE,
            enabled = enabled,
            onClick = { callbacks.onSelectTool(AdjustTool.RESIZE) },
        )
    }
}

/** A preset chip: the name, and underneath it what choosing it produces. */
@Composable
private fun AdjustChip(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .height(48.dp)
            .background(
                color = if (selected) FireOrange else Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else FsText,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) Color.White.copy(alpha = 0.85f) else FsTextDim,
            )
        }
    }
}

/** One tap of the rotate button. Clockwise, matching the icon's arrow. */
private const val QUARTER_TURN = 90

/** Below this the slider is at zero as far as anyone can see, and no op is written. */
private const val ANGLE_EPSILON = 0.05f

/** Guards the reciprocal in the live straighten against a degenerate scale. */
private const val MIN_STRAIGHTEN_SCALE = 0.05f

/** Thirds — the grid people actually compose against. */
private const val GRID_DIVISIONS = 3

/**
 * How far from a corner a touch still counts as grabbing it.
 *
 * Larger than the bracket it grabs, deliberately: the finger covers the corner
 * it is reaching for, so the target has to be the size of the fingertip while
 * the drawing stays the size the photo can spare. Half of the 48 dp minimum
 * target, measured as a radius.
 *
 * Also the grab-radius term of the crop margin (`CropGeometry.reachableInsets`),
 * which is why it is visible to the screen test that measures that margin.
 */
internal const val HANDLE_GRAB_DP = 24

/**
 * How far *into* the frame a touch still counts as grabbing a grip.
 *
 * Twice [HANDLE_GRAB_DP], and inward only: on a photo as wide as the phone the
 * outer half of every left or right grip lies in the back-gesture strip, where
 * the system takes the touch before the app sees it, so the dependable way to
 * take a corner is from inside it. Nothing is drawn for it — the brackets stay
 * the size the photo can spare. It works only because a grip drag keeps the gap
 * it was grabbed at (`grabOffset` in [CropOverlay]); a drag that put the grip
 * under the finger would snap the frame smaller on the first move.
 */
private const val HANDLE_REACH_DP = 48

/** Length of each arm of a corner bracket. */
private const val HANDLE_ARM_DP = 22

/** Bracket thickness — heavy enough to read against a bright sky. */
private const val HANDLE_STROKE_DP = 3

/**
 * Long-edge ceiling for the preview decode. Past a phone's own longest edge the
 * extra pixels cost decode time and show nothing, and the preview is only ever
 * asked to look right — Done re-reads the source at working resolution.
 */
private const val PREVIEW_MAX_DP = 1200

/** The contextual tool panel above the tool row; this screen's alone. */
private const val PANEL_HEIGHT_DP = 64
private const val BOTTOM_PANEL_HEIGHT_DP = PANEL_HEIGHT_DP + EditorChrome.TOOL_ROW_HEIGHT_DP

/**
 * The long edges offered by the resize row, plus `null` for "leave it alone".
 *
 * This is the part of the adjust screen with no WhatsApp equivalent: 1600 is
 * "still a photo", 1080 matches the screen it will most likely be looked at on,
 * and 720 is the one to pick when the point is to get it sent on a bad
 * connection.
 *
 * **Every preset here is at or below the long edge a standard-quality send caps
 * at**, which is what makes §2.5's "an explicit resize wins" true without the
 * send pipeline learning that editing exists (§2.1). A 2048 preset was drafted
 * and dropped for exactly that reason: it takes effect only on an HD send, and
 * on a standard one the compressor would quietly re-cap it to 1600 — a preset
 * that silently does nothing in one of the two quality modes. See §3,
 * departure 7. "Original" is the one row that still defers to the HD pill, and
 * that is the behaviour §2.5 says is unchanged when there is no explicit resize.
 */
private val RESIZE_PRESETS = listOf<Int?>(null, 1600, 1080, 720)
