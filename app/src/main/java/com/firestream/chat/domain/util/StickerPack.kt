package com.firestream.chat.domain.util

/**
 * One flat coloured piece of a sticker, in a `0..1` box.
 *
 * Deliberately primitives rather than arbitrary path data: a circle, a polygon
 * and a stroked polyline cover every sticker in the bundled pack, and each is
 * something both renderers already know how to draw exactly. Arbitrary béziers
 * would have needed a [PathSink] walk on both sides for no sticker that
 * actually wanted one.
 *
 * [colorArgb] is a plain `Long`, like [Stroke.colorArgb], because nothing in
 * `domain/` carries a Compose or Android type.
 */
sealed interface StickerPart {
    /** A filled disc. */
    data class Circle(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        val colorArgb: Long,
    ) : StickerPart

    /** A filled polygon; [points] is `x, y, x, y, …` in the sticker's own `0..1` box. */
    data class Polygon(val points: List<Float>, val colorArgb: Long) : StickerPart

    /**
     * A stroked polyline — the check inside a badge, the rays of a sun.
     * [width] is a fraction of the sticker's box, and the caps are round.
     */
    data class Line(val points: List<Float>, val width: Float, val colorArgb: Long) : StickerPart
}

/**
 * One sticker's design: an id that survives a save, a name a screen reader can
 * use, and its parts in paint order.
 *
 * "Design" rather than "Sticker" because [OverlayContent.Sticker] is the placed
 * instance — this is what it names, and the two are worth being able to say
 * apart in one sentence.
 */
data class StickerDesign(val id: String, val name: String, val parts: List<StickerPart>)

/**
 * The bundled sticker pack — the local set the editor's sticker tab offers
 * (`.claude/plans/image-editor.md` §3 Phase 5).
 *
 * ### Why these are drawn rather than decoded
 *
 * A sticker pack is usually a folder of PNGs. These are shapes instead, for the
 * same reason [StrokeGeometry] exists: the editor previews a sticker with
 * Compose and the flatten paints it with `android.graphics`, and a bitmap would
 * have to be decoded, scaled and filtered twice — two chances for the preview to
 * promise a placement the file does not deliver, plus an asset whose intrinsic
 * size the geometry would have to ask about. A part list in a `0..1` box has no
 * intrinsic size and no decode: both renderers scale the same numbers into their
 * own space and cannot disagree.
 *
 * It also keeps the pack in `domain/`, where the picker grid (`ui/`) and the
 * rasterizer (`data/`) can both reach it without either importing the other.
 *
 * ### What is in it
 *
 * Marks, not characters. The emoji tab already offers every face there is; what
 * a photo being annotated actually wants is the vocabulary of pointing at
 * things and passing judgement on them — a tick, a cross, an arrow, a warning
 * triangle, a pin. Each is flat, high-contrast and readable at thumbnail size
 * over an arbitrary photo, which is what separates a sticker from a shape:
 * [OverlayContent.Shape] takes the colour you choose, a sticker comes with its
 * own.
 *
 * Pack management — downloadable packs, ordering, removal — is its own feature
 * and stays in `docs/BACKLOG.md` §4.6.
 */
object StickerPack {

    /** Every sticker, in the order the tab shows them. */
    val stickers: List<StickerDesign> = listOf(
        heart(),
        star(),
        tick(),
        cross(),
        warning(),
        pin(),
        bolt(),
        arrow(),
        sparkle(),
        target(),
        sun(),
        speechBubble(),
    )

    private val index: Map<String, StickerDesign> = stickers.associateBy { it.id }

    /** The design an [OverlayContent.Sticker] names, or null if the id is unknown. */
    fun byId(id: String): StickerDesign? = index[id]

    // ── The pack ─────────────────────────────────────────────────────────────
    //
    // Every sticker is built from the three primitives above in a 0..1 box, with
    // y running down the way a canvas does. Coordinates are written out rather
    // than computed so each shape can be read and corrected in place.

    private const val RED = 0xFFE53935
    private const val DEEP_RED = 0xFFC62828
    private const val GOLD = 0xFFFFC107
    private const val AMBER = 0xFFFF8F00
    private const val GREEN = 0xFF2E7D32
    private const val BLUE = 0xFF1E88E5
    private const val WHITE = 0xFFFFFFFF
    private const val INK = 0xFF212121

    /** Two lobes and a point — the classic construction, and it reads at any size. */
    private fun heart() = StickerDesign(
        id = "heart",
        name = "Heart",
        parts = listOf(
            StickerPart.Circle(0.28f, 0.32f, 0.28f, RED),
            StickerPart.Circle(0.72f, 0.32f, 0.28f, RED),
            StickerPart.Polygon(listOf(0.01f, 0.36f, 0.99f, 0.36f, 0.5f, 0.97f), RED),
        ),
    )

    /** A five-point star, points computed once and written down. */
    private fun star() = StickerDesign(
        id = "star",
        name = "Star",
        parts = listOf(
            StickerPart.Polygon(
                listOf(
                    0.50f, 0.02f,
                    0.62f, 0.36f,
                    0.98f, 0.36f,
                    0.69f, 0.58f,
                    0.80f, 0.94f,
                    0.50f, 0.72f,
                    0.20f, 0.94f,
                    0.31f, 0.58f,
                    0.02f, 0.36f,
                    0.38f, 0.36f,
                ),
                GOLD,
            ),
        ),
    )

    /** A green badge with a white tick — the most-used mark on an annotated photo. */
    private fun tick() = StickerDesign(
        id = "tick",
        name = "Tick",
        parts = listOf(
            StickerPart.Circle(0.5f, 0.5f, 0.48f, GREEN),
            StickerPart.Line(listOf(0.26f, 0.52f, 0.44f, 0.70f, 0.76f, 0.32f), 0.13f, WHITE),
        ),
    )

    /** The same badge, the other verdict. */
    private fun cross() = StickerDesign(
        id = "cross",
        name = "Cross",
        parts = listOf(
            StickerPart.Circle(0.5f, 0.5f, 0.48f, RED),
            StickerPart.Line(listOf(0.30f, 0.30f, 0.70f, 0.70f), 0.13f, WHITE),
            StickerPart.Line(listOf(0.70f, 0.30f, 0.30f, 0.70f), 0.13f, WHITE),
        ),
    )

    /** An amber triangle with a bar and a dot: "look at this". */
    private fun warning() = StickerDesign(
        id = "warning",
        name = "Warning",
        parts = listOf(
            StickerPart.Polygon(listOf(0.50f, 0.03f, 0.99f, 0.93f, 0.01f, 0.93f), AMBER),
            StickerPart.Line(listOf(0.50f, 0.36f, 0.50f, 0.63f), 0.11f, INK),
            StickerPart.Circle(0.50f, 0.79f, 0.065f, INK),
        ),
    )

    /** A map pin — a disc over a point, with a hole through it. */
    private fun pin() = StickerDesign(
        id = "pin",
        name = "Pin",
        parts = listOf(
            StickerPart.Circle(0.5f, 0.36f, 0.36f, DEEP_RED),
            StickerPart.Polygon(listOf(0.22f, 0.55f, 0.78f, 0.55f, 0.50f, 0.99f), DEEP_RED),
            StickerPart.Circle(0.5f, 0.36f, 0.14f, WHITE),
        ),
    )

    /** A lightning bolt. */
    private fun bolt() = StickerDesign(
        id = "bolt",
        name = "Bolt",
        parts = listOf(
            StickerPart.Polygon(
                listOf(
                    0.58f, 0.02f,
                    0.20f, 0.55f,
                    0.45f, 0.55f,
                    0.36f, 0.98f,
                    0.80f, 0.42f,
                    0.53f, 0.42f,
                ),
                GOLD,
            ),
        ),
    )

    /** A solid arrow, pointing right so a rotation aims it anywhere. */
    private fun arrow() = StickerDesign(
        id = "arrow",
        name = "Arrow",
        parts = listOf(
            StickerPart.Polygon(
                listOf(
                    0.02f, 0.38f,
                    0.58f, 0.38f,
                    0.58f, 0.16f,
                    0.98f, 0.50f,
                    0.58f, 0.84f,
                    0.58f, 0.62f,
                    0.02f, 0.62f,
                ),
                AMBER,
            ),
        ),
    )

    /** A four-point sparkle — the "new" / "look" mark, concave so it reads as a glint. */
    private fun sparkle() = StickerDesign(
        id = "sparkle",
        name = "Sparkle",
        parts = listOf(
            StickerPart.Polygon(
                listOf(
                    0.50f, 0.00f,
                    0.60f, 0.40f,
                    1.00f, 0.50f,
                    0.60f, 0.60f,
                    0.50f, 1.00f,
                    0.40f, 0.60f,
                    0.00f, 0.50f,
                    0.40f, 0.40f,
                ),
                GOLD,
            ),
        ),
    )

    /** Concentric rings — "exactly here", when a pin is too heavy. */
    private fun target() = StickerDesign(
        id = "target",
        name = "Target",
        parts = listOf(
            StickerPart.Circle(0.5f, 0.5f, 0.48f, RED),
            StickerPart.Circle(0.5f, 0.5f, 0.33f, WHITE),
            StickerPart.Circle(0.5f, 0.5f, 0.18f, RED),
        ),
    )

    /** A disc with eight rays. */
    private fun sun() = StickerDesign(
        id = "sun",
        name = "Sun",
        parts = listOf(
            StickerPart.Circle(0.5f, 0.5f, 0.27f, GOLD),
            StickerPart.Line(listOf(0.50f, 0.02f, 0.50f, 0.16f), 0.09f, GOLD),
            StickerPart.Line(listOf(0.50f, 0.84f, 0.50f, 0.98f), 0.09f, GOLD),
            StickerPart.Line(listOf(0.02f, 0.50f, 0.16f, 0.50f), 0.09f, GOLD),
            StickerPart.Line(listOf(0.84f, 0.50f, 0.98f, 0.50f), 0.09f, GOLD),
            StickerPart.Line(listOf(0.16f, 0.16f, 0.26f, 0.26f), 0.09f, GOLD),
            StickerPart.Line(listOf(0.74f, 0.74f, 0.84f, 0.84f), 0.09f, GOLD),
            StickerPart.Line(listOf(0.74f, 0.26f, 0.84f, 0.16f), 0.09f, GOLD),
            StickerPart.Line(listOf(0.26f, 0.74f, 0.16f, 0.84f), 0.09f, GOLD),
        ),
    )

    /** A speech bubble with a tail and an ellipsis — "they said this". */
    private fun speechBubble() = StickerDesign(
        id = "speech",
        name = "Speech bubble",
        parts = listOf(
            StickerPart.Polygon(
                listOf(0.03f, 0.10f, 0.97f, 0.10f, 0.97f, 0.70f, 0.42f, 0.70f, 0.22f, 0.97f, 0.24f, 0.70f, 0.03f, 0.70f),
                BLUE,
            ),
            StickerPart.Circle(0.30f, 0.40f, 0.075f, WHITE),
            StickerPart.Circle(0.50f, 0.40f, 0.075f, WHITE),
            StickerPart.Circle(0.70f, 0.40f, 0.075f, WHITE),
        ),
    )
}
