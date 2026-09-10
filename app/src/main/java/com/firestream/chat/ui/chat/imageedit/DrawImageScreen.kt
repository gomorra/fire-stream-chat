package com.firestream.chat.ui.chat.imageedit

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.util.PathSink
import com.firestream.chat.domain.util.Stroke
import com.firestream.chat.domain.util.StrokeGeometry
import com.firestream.chat.domain.util.StrokePoint
import com.firestream.chat.domain.util.StrokeTool
import com.firestream.chat.ui.theme.FireOrange
import com.firestream.chat.ui.theme.FsText
import com.firestream.chat.ui.theme.FsTextDim
import com.firestream.chat.ui.theme.FsTextMute
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Every action the draw screen's chrome can fire, in one immutable bundle.
 *
 * Tool, colour, width, stroke, undo, redo, layer, cancel and done is nine
 * callbacks before a single piece of state, and this repo's Composable
 * parameter ceiling (~15) is enforced by ART with a **`VerifyError` on first
 * render, not at compile time** — Robolectric runs on the JVM and would let the
 * tests below pass while the editor crashed on a real phone. Same shape and same
 * reason as `MessageBubbleCallbacks` and [AdjustCallbacks].
 */
@Immutable
internal data class DrawCallbacks(
    val onSelectTool: (StrokeTool) -> Unit,
    val onSelectColor: (Long) -> Unit,
    val onWidth: (Float) -> Unit,
    /** One finger-down to finger-up, already normalized to the image. */
    val onStroke: (Stroke) -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onToggleLayer: () -> Unit,
    val onCancel: () -> Unit,
    val onDone: () -> Unit,
)

/**
 * Pen, highlighter and blur over one image, as a full-screen overlay on the
 * send preview.
 *
 * ### What Done writes
 *
 * The screen builds a [DrawStack] of strokes and flattens it **once**, on Done,
 * into a single `RasterOp.Strokes` and a single new JPEG that becomes one entry
 * in the preview's edit history (`.claude/plans/image-editor.md` §2.1). Cancel
 * writes nothing. Nothing here is a NavHost route (§2.4).
 *
 * ### The preview and the file must not be able to disagree
 *
 * This is the screen where that stops being a matter of polish. A blur is a
 * redaction, and a redaction that covered a face on screen and missed it in the
 * JPEG is a privacy failure, not a cosmetic one. Three things enforce the
 * agreement:
 *
 * - The photo comes from [ImageEditServices.renderPreview], which runs the
 *   *same* decode the rasterizer will, so the pixels under the finger are the
 *   pixels Done reads.
 * - Every number a stroke is drawn with — its width, its curve, the coarseness
 *   of the mosaic — comes from `StrokeGeometry`, which both this screen and
 *   `ImageEditRasterizer` call. Only the painting itself is written twice, and
 *   only because Compose and `android.graphics` share no path object.
 * - The mosaic itself comes from [ImageEditServices.pixelate], so the blocks
 *   the user checks are the blocks that get written, scaled.
 *
 * ### The eye is a view control
 *
 * Hiding the layer shows the photo underneath so a redaction can be checked
 * against what it was meant to cover — which is why this screen has the toggle
 * and the adjust screen deliberately does not (§2.7). **It never changes what
 * gets written**: Done with the layer hidden still flattens every stroke, and
 * re-entering the screen always comes back visible. Drawing is suspended while
 * it is hidden, because a stroke that landed invisibly would be worse feedback
 * than none.
 */
@Composable
internal fun DrawImageScreen(
    source: Uri,
    onDone: (Uri) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    services: ImageEditServices = ImageEditServices(),
    /**
     * Every batch item's current step, read at the moment Done flattens rather
     * than captured when the editor opened — see [AdjustImageScreen] for why a
     * forgotten set silently deletes an edit on another page.
     */
    liveSteps: () -> Set<Uri> = { emptySet() },
) {
    val scope = rememberCoroutineScope()

    var stack by rememberSaveable(source, stateSaver = DrawStack.StackSaver) {
        mutableStateOf(DrawStack())
    }
    var tool by rememberSaveable(source) { mutableStateOf(StrokeTool.PEN) }
    var color by rememberSaveable(source) { mutableLongStateOf(DRAW_COLORS.first()) }
    var width by rememberSaveable(source) { mutableFloatStateOf(StrokeGeometry.DEFAULT_WIDTH) }
    // Saved so a rotation does not flip the layer back on mid-check, but not
    // carried across a visit: the screen leaves the composition when it closes,
    // which drops the saved value, and §2.7 wants it visible on every entry.
    var layerVisible by rememberSaveable(source) { mutableStateOf(true) }

    var preview by remember(source) { mutableStateOf<Bitmap?>(null) }
    var mosaic by remember(source) { mutableStateOf<Bitmap?>(null) }
    var flattening by remember(source) { mutableStateOf(false) }
    var failed by remember(source) { mutableStateOf(false) }

    LaunchedEffect(source, services) {
        preview = services.renderPreview(source, emptyList(), PREVIEW_MAX_PX)
    }
    // Computed as soon as the photo lands rather than when the first blur stroke
    // starts: it is a few thousand pixels, and finding out how coarse the
    // redaction is halfway through the stroke that makes it is no use to anyone.
    LaunchedEffect(preview) {
        mosaic = preview?.let { services.pixelate(it) }
    }

    val callbacks = DrawCallbacks(
        onSelectTool = { tool = it },
        onSelectColor = { color = it },
        onWidth = { width = it },
        onStroke = { stack = stack.push(it) },
        onUndo = { stack = stack.undo() },
        onRedo = { stack = stack.redo() },
        onToggleLayer = { layerVisible = !layerVisible },
        onCancel = onCancel,
        onDone = {
            val op = stack.toOp()
            if (op == null) {
                // Nothing was drawn, so there is nothing to flatten. Writing a
                // re-encoded copy of the photo anyway would burn a history step,
                // a cache file and a generation of JPEG quality on a no-op —
                // the same rule the adjust screen's Done follows.
                onCancel()
            } else {
                flattening = true
                failed = false
                scope.launch {
                    // `stack`, not what is on screen: the layer-eye is a view
                    // control and hiding it must not quietly drop the strokes.
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
        DrawCanvas(
            preview = preview,
            mosaic = mosaic,
            strokes = stack.active,
            style = DrawStyle(tool = tool, color = color, width = width, visible = layerVisible),
            enabled = layerVisible && !flattening,
            onStroke = callbacks.onStroke,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = TOP_BAR_HEIGHT_DP.dp, bottom = BOTTOM_PANEL_HEIGHT_DP.dp),
        )

        DrawTopBar(
            canUndo = stack.canUndo,
            canRedo = stack.canRedo,
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
            AnimatedVisibility(
                visible = failed,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Text(
                    text = "Couldn't apply the drawing. Try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            ColourRow(tool = tool, selected = color, onSelect = callbacks.onSelectColor)
            WidthRow(width = width, tool = tool, color = color, onWidth = callbacks.onWidth)
            DrawToolRow(
                tool = tool,
                layerVisible = layerVisible,
                enabled = !flattening,
                callbacks = callbacks,
            )
        }

        if (flattening) EditFlattenScrim()
    }
}

/** What the next stroke will look like, and whether the drawn ones are shown at all. */
@Immutable
private data class DrawStyle(
    val tool: StrokeTool,
    val color: Long,
    val width: Float,
    val visible: Boolean,
)

/**
 * The photo, every stroke over it, and the finger that is drawing the next one.
 *
 * **One `Canvas` for all of it**, deliberately. Phase 3 shipped a bug where the
 * photo's container centred it *and* [ImageFitMapper] centred it again, putting
 * every crop handle half a letterbox from the pixels it belonged to. Drawing the
 * bitmap and the strokes into the same canvas at the same mapper's rect removes
 * the possibility rather than re-avoiding it: there is one coordinate space here
 * and one thing that positions anything in it.
 *
 * The in-progress stroke is a `SnapshotStateList` read inside the draw lambda,
 * so a moving finger invalidates the *draw* phase only — no recomposition per
 * pointer event. Paths are rebuilt each frame rather than cached: a few hundred
 * `quadraticTo` calls is far below a frame budget, and a cache keyed on the
 * strokes *and* the mapper would be invalidated by every resize anyway.
 */
@Composable
private fun DrawCanvas(
    preview: Bitmap?,
    mosaic: Bitmap?,
    strokes: List<Stroke>,
    style: DrawStyle,
    enabled: Boolean,
    onStroke: (Stroke) -> Unit,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val live = remember { mutableStateListOf<StrokePoint>() }

    Box(modifier = modifier.onSizeChanged { canvasSize = it }, contentAlignment = Alignment.Center) {
        val bitmap = preview
        if (bitmap == null) {
            CircularProgressIndicator(color = FsTextMute)
            return@Box
        }

        val mapper = ImageFitMapper(
            canvasWidth = canvasSize.width.toFloat(),
            canvasHeight = canvasSize.height.toFloat(),
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
        )
        if (mapper.scale <= 0f) return@Box

        val image = remember(bitmap) { bitmap.asImageBitmap() }
        val mosaicImage = remember(mosaic) { mosaic?.asImageBitmap() }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Drawing canvas" }
                .pointerInput(mapper, style, enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        live.clear()
                        live += mapper.normalizedPoint(down.position)

                        var pointer = down
                        while (pointer.pressed) {
                            val event = awaitPointerEvent()
                            // Only the finger that started the stroke; a second
                            // one landing mid-drag must not tug the line to it.
                            pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!pointer.pressed) break
                            val point = mapper.normalizedPoint(pointer.position)
                            if (StrokeGeometry.shouldAppend(live.lastOrNull(), point)) live += point
                            pointer.consume()
                        }

                        if (live.isNotEmpty()) {
                            onStroke(
                                Stroke(
                                    tool = style.tool,
                                    colorArgb = style.color,
                                    width = style.width,
                                    points = live.toList(),
                                ),
                            )
                        }
                        live.clear()
                    }
                },
        ) {
            drawImage(
                image = image,
                dstOffset = IntOffset(mapper.offsetX.roundToInt(), mapper.offsetY.roundToInt()),
                dstSize = IntSize(mapper.fittedWidth.roundToInt(), mapper.fittedHeight.roundToInt()),
            )
            if (!style.visible) return@Canvas

            // Clipped to the photo, so a stroke that ran off the edge stops at
            // the edge on screen exactly as it will in the file, where the
            // bitmap's own bounds do the clipping.
            clipRect(
                left = mapper.offsetX,
                top = mapper.offsetY,
                right = mapper.offsetX + mapper.fittedWidth,
                bottom = mapper.offsetY + mapper.fittedHeight,
            ) {
                val drawing = if (live.isEmpty()) {
                    strokes
                } else {
                    strokes + Stroke(style.tool, style.color, style.width, live.toList())
                }
                drawStrokeLayers(drawing, mapper, mosaicImage)
            }
        }
    }
}

/**
 * Every stroke, in the two layers `StrokeGeometry.layers` splits them into:
 * the blur mask revealing the mosaic underneath, then the painted marks on top.
 *
 * The blur layer is one `saveLayer` for all of it, not one per stroke, which is
 * what makes overlapping blur strokes reveal the same pixels rather than
 * compounding into something darker — and it is the same shape the rasterizer's
 * `drawStrokes` uses, so the two agree by construction.
 */
private fun DrawScope.drawStrokeLayers(
    strokes: List<Stroke>,
    mapper: ImageFitMapper,
    mosaic: ImageBitmap?,
) {
    val layers = StrokeGeometry.layers(strokes)
    val longEdge = maxOf(mapper.fittedWidth, mapper.fittedHeight)

    if (layers.blur.isNotEmpty() && mosaic != null) {
        val bounds = Rect(
            left = mapper.offsetX,
            top = mapper.offsetY,
            right = mapper.offsetX + mapper.fittedWidth,
            bottom = mapper.offsetY + mapper.fittedHeight,
        )
        drawContext.canvas.saveLayer(bounds, Paint())
        for (stroke in layers.blur) {
            drawOneStroke(stroke, mapper, longEdge, Color.White, alpha = 1f, blend = BlendMode.SrcOver)
        }
        drawImage(
            image = mosaic,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(mosaic.width, mosaic.height),
            dstOffset = IntOffset(mapper.offsetX.roundToInt(), mapper.offsetY.roundToInt()),
            dstSize = IntSize(mapper.fittedWidth.roundToInt(), mapper.fittedHeight.roundToInt()),
            blendMode = BlendMode.SrcIn,
            // Nearest-neighbour, which is the half of "pixelate" that redacts:
            // a smooth upscale would be a blur, and a blur can be squinted at.
            filterQuality = FilterQuality.None,
        )
        drawContext.canvas.restore()
    }

    for (stroke in layers.painted) {
        drawOneStroke(
            stroke = stroke,
            mapper = mapper,
            longEdge = longEdge,
            color = Color(stroke.colorArgb),
            alpha = StrokeGeometry.alphaFor(stroke.tool),
            blend = if (stroke.tool == StrokeTool.HIGHLIGHTER) BlendMode.Multiply else BlendMode.SrcOver,
        )
    }
}

/** One stroke — a round-capped path, or a dot when the finger never moved. */
private fun DrawScope.drawOneStroke(
    stroke: Stroke,
    mapper: ImageFitMapper,
    longEdge: Float,
    color: Color,
    alpha: Float,
    blend: BlendMode,
) {
    val width = StrokeGeometry.widthPx(stroke.width, longEdge)
    if (StrokeGeometry.isDot(stroke)) {
        val screen = mapper.normalizedToScreen(stroke.points.first().toFit())
        drawCircle(
            color = color,
            radius = width / 2f,
            center = Offset(screen.x, screen.y),
            alpha = alpha,
            blendMode = blend,
        )
        return
    }
    val path = Path()
    StrokeGeometry.buildPath(
        points = stroke.points,
        scaleX = mapper.fittedWidth,
        scaleY = mapper.fittedHeight,
        offsetX = mapper.offsetX,
        offsetY = mapper.offsetY,
        sink = object : PathSink {
            override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
            override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
            override fun quadTo(controlX: Float, controlY: Float, x: Float, y: Float) =
                path.quadraticTo(controlX, controlY, x, y)
        },
    )
    drawPath(
        path = path,
        color = color,
        alpha = alpha,
        style = StrokeStyle(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = blend,
    )
}

/** Cancel and Done at the ends, undo and redo beside the cancel. */
@Composable
private fun DrawTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    enabled: Boolean,
    callbacks: DrawCallbacks,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT_DP.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = callbacks.onCancel, enabled = enabled) {
            Icon(Icons.Default.Close, contentDescription = "Cancel drawing", tint = FsText)
        }

        Spacer(modifier = Modifier.width(4.dp))

        IconButton(onClick = callbacks.onUndo, enabled = enabled && canUndo) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Undo,
                contentDescription = "Undo stroke",
                tint = if (enabled && canUndo) FsText else FsTextMute,
            )
        }
        IconButton(onClick = callbacks.onRedo, enabled = enabled && canRedo) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Redo,
                contentDescription = "Redo stroke",
                tint = if (enabled && canRedo) FsText else FsTextMute,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        IconButton(onClick = callbacks.onDone, enabled = enabled) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Apply drawing",
                tint = if (enabled) FireOrange else FsTextMute,
            )
        }
    }
}

/**
 * The colour swatches — or, for blur, a line saying what blur does instead.
 *
 * The row keeps its height either way so the photo above it does not jump when
 * the tool changes, and the caption is more use than an empty gap: "blur has no
 * colour" is the question the missing swatches would otherwise raise.
 */
@Composable
private fun ColourRow(tool: StrokeTool, selected: Long, onSelect: (Long) -> Unit) {
    if (tool == StrokeTool.BLUR) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(COLOUR_ROW_HEIGHT_DP.dp)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "Paint over anything you want hidden — it is pixelated for good.",
                style = MaterialTheme.typography.bodySmall,
                color = FsTextDim,
            )
        }
        return
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(COLOUR_ROW_HEIGHT_DP.dp)
            .semantics { contentDescription = "Stroke colours" },
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(DRAW_COLORS, key = { it }) { swatch ->
            val isSelected = swatch == selected
            Box(
                // A 44 dp box around a 26 dp dot: the swatches read as a tight
                // strip while every one of them is still a real touch target.
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClickLabel = "Choose colour") { onSelect(swatch) }
                    .semantics { contentDescription = colourName(swatch) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(if (isSelected) 30.dp else 26.dp)
                        .background(Color(swatch), CircleShape)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) FireOrange else Color.White.copy(alpha = 0.35f),
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

/**
 * The width slider, with a dot showing what it will draw.
 *
 * A *tool* property, not a selection property — nothing has to be selected for
 * it to mean something. Phase 5's overlay screen deliberately does not get a
 * slider that looks like this one for exactly that reason (§4).
 */
@Composable
private fun WidthRow(width: Float, tool: StrokeTool, color: Long, onWidth: (Float) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(WIDTH_ROW_HEIGHT_DP.dp)
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slider(
            value = width,
            onValueChange = onWidth,
            valueRange = StrokeGeometry.MIN_WIDTH..StrokeGeometry.MAX_WIDTH,
            colors = SliderDefaults.colors(
                thumbColor = FireOrange,
                activeTrackColor = FireOrange,
                inactiveTrackColor = FsTextMute,
            ),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Stroke width" },
        )
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier.size(WIDTH_PREVIEW_BOX_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            val fraction = (width - StrokeGeometry.MIN_WIDTH) /
                (StrokeGeometry.MAX_WIDTH - StrokeGeometry.MIN_WIDTH)
            Box(
                modifier = Modifier
                    .size((WIDTH_PREVIEW_MIN_DP + fraction * (WIDTH_PREVIEW_MAX_DP - WIDTH_PREVIEW_MIN_DP)).dp)
                    .background(
                        color = when (tool) {
                            StrokeTool.BLUR -> FsTextDim
                            else -> Color(color).copy(alpha = StrokeGeometry.alphaFor(tool))
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

/**
 * The three tools, and the layer-visibility eye at the end of the same row.
 *
 * The eye sits **with the tools, not with the actions** (§2.7): it is a view
 * control, and putting it beside undo and Done would imply it changes the
 * drawing. It does not — Done flattens every stroke whether the layer is shown
 * or hidden.
 */
@Composable
private fun DrawToolRow(
    tool: StrokeTool,
    layerVisible: Boolean,
    enabled: Boolean,
    callbacks: DrawCallbacks,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TOOL_ROW_HEIGHT_DP.dp)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DrawToolButton(Icons.Default.Edit, "Pen", tool == StrokeTool.PEN, enabled) {
                callbacks.onSelectTool(StrokeTool.PEN)
            }
            DrawToolButton(
                icon = Icons.Outlined.Brush,
                label = "Highlighter",
                selected = tool == StrokeTool.HIGHLIGHTER,
                enabled = enabled,
                onClick = { callbacks.onSelectTool(StrokeTool.HIGHLIGHTER) },
            )
            DrawToolButton(Icons.Outlined.BlurOn, "Blur", tool == StrokeTool.BLUR, enabled) {
                callbacks.onSelectTool(StrokeTool.BLUR)
            }
        }

        IconButton(
            onClick = callbacks.onToggleLayer,
            enabled = enabled,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = if (layerVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = if (layerVisible) "Hide the drawing" else "Show the drawing",
                tint = when {
                    !enabled -> FsTextMute
                    layerVisible -> FsText
                    else -> FireOrange
                },
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun DrawToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> FsTextMute
        selected -> FireOrange
        else -> FsText
    }
    Column(
        modifier = Modifier
            .size(width = 76.dp, height = TOOL_ROW_HEIGHT_DP.dp)
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

/** A screen point as a fraction of the image, which is how a stroke is stored. */
private fun ImageFitMapper.normalizedPoint(position: Offset): StrokePoint {
    val normalized = screenToNormalized(FitPoint(position.x, position.y))
    return StrokePoint(normalized.x, normalized.y)
}

private fun StrokePoint.toFit(): FitPoint = FitPoint(x, y)

/** The name a screen reader reads for a swatch, and the handle a test grabs it by. */
private fun colourName(argb: Long): String = when (argb) {
    0xFFFFFFFF -> "White"
    0xFF000000 -> "Black"
    0xFFF26A1F -> "Orange"
    0xFFE53935 -> "Red"
    0xFFFFD600 -> "Yellow"
    0xFF43A047 -> "Green"
    0xFF1E88E5 -> "Blue"
    else -> "Purple"
}

/**
 * The swatches, in the order they appear.
 *
 * White and black first because an annotation's job is to be seen against a
 * photo and one of the two always is; the brand orange next because it is what
 * the rest of the app marks things with; then the five hues an annotation
 * usually reaches for. Stored as ARGB `Long`s, which is what a `RasterOp` carries
 * — no Compose type reaches the domain layer.
 */
private val DRAW_COLORS = listOf(
    0xFFFFFFFF,
    0xFF000000,
    0xFFF26A1F,
    0xFFE53935,
    0xFFFFD600,
    0xFF43A047,
    0xFF1E88E5,
    0xFFAB47BC,
)

/**
 * Long-edge ceiling for the preview decode, in pixels rather than dp: this is a
 * decode target, and it should not change with the display's density the way a
 * layout does. Past a phone's own longest edge the extra pixels cost decode time
 * and show nothing — Done re-reads the source at working resolution.
 */
private const val PREVIEW_MAX_PX = 1600

private const val TOP_BAR_HEIGHT_DP = 56
private const val COLOUR_ROW_HEIGHT_DP = 52
private const val WIDTH_ROW_HEIGHT_DP = 48
private const val TOOL_ROW_HEIGHT_DP = 68
private const val BOTTOM_PANEL_HEIGHT_DP =
    COLOUR_ROW_HEIGHT_DP + WIDTH_ROW_HEIGHT_DP + TOOL_ROW_HEIGHT_DP

private const val WIDTH_PREVIEW_BOX_DP = 34
private const val WIDTH_PREVIEW_MIN_DP = 4f
private const val WIDTH_PREVIEW_MAX_DP = 30f
