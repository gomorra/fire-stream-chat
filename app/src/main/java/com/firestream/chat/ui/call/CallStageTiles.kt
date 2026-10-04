package com.firestream.chat.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.firestream.chat.ui.components.rememberAvatarRequest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The stage is dark whatever the app theme, so its colours are its own. */
internal object StageColors {
    val Black = Color(0xFF08090A)
    val Dock = Color(0xE617181B)
    val Glass = Color(0x2EFFFFFF)
    val DimText = Color(0xB8FFFFFF)
    val HangUp = Color(0xFFE53935)
    val Answer = Color(0xFF43A047)

    /** White when lit, glass at rest. */
    val Controls = CallControlColors(
        background = Glass,
        icon = Color.White,
        litBackground = Color.White,
        litIcon = Black,
    )
}

internal object CallStageTags {
    const val AVATAR = "call_avatar"
    const val SELF_TILE = "call_self_tile"
    const val DOCK = "call_dock"
    const val STAGE = "call_stage"
}

/** Who the stage shows while it has no video of them. */
@Immutable
internal data class StagePerson(
    val name: String,
    val avatarUrl: String? = null,
    val localAvatarPath: String? = null,
)

/** The quiet glow behind a call without video. It is opaque, so it covers a tile below it. */
@Composable
internal fun StageGlow(modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxSize()
            .background(StageColors.Black)
            .background(Brush.radialGradient(0f to tint.copy(alpha = 0.26f), 1f to Color.Transparent, radius = 900f))
    )
}

@Composable
internal fun StageAvatar(person: StagePerson, diameter: Dp, modifier: Modifier = Modifier) {
    val request = rememberAvatarRequest(person.localAvatarPath, person.avatarUrl)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .testTag(CallStageTags.AVATAR),
    ) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = person.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = person.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = if (diameter >= 96.dp) {
                    MaterialTheme.typography.displaySmall
                } else {
                    MaterialTheme.typography.titleLarge
                },
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * The avatar, the name and one line below it: the ring text, or the timer of a voice call.
 *
 * @param pulsing the avatar breathes, for a ring nobody has answered yet.
 */
@Composable
internal fun StageIdentity(
    person: StagePerson,
    status: () -> String,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    pulsing: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.padding(horizontal = 24.dp)) {
        Box(Modifier.pulse(pulsing)) { StageAvatar(person, 132.dp) }
        Spacer(Modifier.height(24.dp))
        Text(
            text = person.name,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        StageStatus(status, MaterialTheme.typography.bodyLarge)
        if (muted) {
            Spacer(Modifier.height(12.dp))
            MutedMark()
        }
    }
}

/** The one line under a name. It reads [text] itself, so a running timer recomposes only this. */
@Composable
internal fun StageStatus(text: () -> String, style: TextStyle, color: Color = StageColors.DimText) {
    Text(text = text(), style = style, color = color, maxLines = 1)
}

@Composable
private fun Modifier.pulse(on: Boolean): Modifier {
    if (!on) return this
    val transition = rememberInfiniteTransition(label = "ring")
    val alpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "ring_alpha",
    )
    return graphicsLayer { this.alpha = alpha }
}

/** The other side says its microphone is closed. */
@Composable
internal fun MutedMark(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background(StageColors.Glass)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Icon(Icons.Default.MicOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("Muted", style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

// ── The floating tile ───────────────────────────────────────────────────────

private val TileWidth = 108.dp
private val TileHeight = 152.dp
private val TileShape = RoundedCornerShape(18.dp)

/**
 * A rounded tile above the stage. It follows the finger and snaps to the nearest corner when it is
 * let go. The corners move up while the dock shows, so the tile never rests under it.
 */
@Composable
internal fun BoxWithConstraintsScope.FloatingTile(
    visible: Boolean,
    dockShown: Boolean,
    onTap: () -> Unit,
    content: @Composable () -> Unit,
) {
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val topEdge = safe.calculateTopPadding() + 64.dp
    val bottomEdge = safe.calculateBottomPadding() + if (dockShown) 124.dp else 20.dp
    val side = 14.dp
    val bounds = with(LocalDensity.current) {
        Rect(
            left = side.toPx(),
            top = topEdge.toPx(),
            right = (maxWidth - side - TileWidth).toPx(),
            bottom = (maxHeight - bottomEdge - TileHeight).toPx(),
        )
    }

    var corner by rememberSaveable { mutableIntStateOf(SelfTileCorners.BOTTOM_END) }
    val position = remember { Animatable(SelfTileCorners.offsetOf(corner, bounds), Offset.VectorConverter) }
    val settle = spring<Offset>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
    LaunchedEffect(corner, bounds) { position.animateTo(SelfTileCorners.offsetOf(corner, bounds), settle) }

    val scope = rememberCoroutineScope()
    val currentOnTap by rememberUpdatedState(onTap)
    // Read when the tile is let go. As a key it would end a drag the moment the dock hides.
    val currentBounds by rememberUpdatedState(bounds)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.7f),
        exit = fadeOut() + scaleOut(targetScale = 0.7f),
        modifier = Modifier.offset { IntOffset(position.value.x.roundToInt(), position.value.y.roundToInt()) },
    ) {
        Box(
            modifier = Modifier
                .size(TileWidth, TileHeight)
                .shadow(10.dp, TileShape)
                .clip(TileShape)
                .background(StageColors.Black)
                .border(1.dp, Color.White.copy(alpha = 0.16f), TileShape)
                .testTag(CallStageTags.SELF_TILE)
                .pointerInput(Unit) { detectTapGestures { currentOnTap() } }
                .pointerInput(Unit) {
                    // The drag sends where the tile should be, worked out from where it was
                    // grabbed. A step added to the current position would count twice when two
                    // events arrive before the next frame.
                    var grabbedAt = Offset.Zero
                    var moved = Offset.Zero
                    val letGo = {
                        corner = SelfTileCorners.nearest(position.value, currentBounds)
                        scope.launch { position.animateTo(SelfTileCorners.offsetOf(corner, currentBounds), settle) }
                        Unit
                    }
                    detectDragGestures(
                        onDragStart = {
                            grabbedAt = position.value
                            moved = Offset.Zero
                        },
                        onDragEnd = letGo,
                        onDragCancel = letGo,
                        onDrag = { change, amount ->
                            change.consume()
                            moved += amount
                            val target = grabbedAt + moved
                            scope.launch { position.snapTo(target) }
                        },
                    )
                },
        ) {
            content()
        }
    }
}

/** Where the floating tile can rest. [Rect] holds the tile's top-left position in each corner. */
internal object SelfTileCorners {
    const val TOP_START = 0
    const val TOP_END = 1
    const val BOTTOM_START = 2
    const val BOTTOM_END = 3

    fun offsetOf(corner: Int, bounds: Rect) = Offset(
        x = if (corner % 2 == 0) bounds.left else bounds.right,
        y = if (corner < 2) bounds.top else bounds.bottom,
    )

    /** The corner closest to a tile whose top-left is [at]. */
    fun nearest(at: Offset, bounds: Rect): Int {
        val end = at.x > (bounds.left + bounds.right) / 2f
        val bottom = at.y > (bounds.top + bounds.bottom) / 2f
        return (if (bottom) 2 else 0) + (if (end) 1 else 0)
    }
}
