package com.firestream.chat.ui.chat.picker

/**
 * A tab the picker can offer, and the seam that keeps the picker honest about
 * what each of its hosts can actually do.
 *
 * The island of tabs in [PickerPanel] is **declared by the host**, not fixed
 * (`.claude/plans/image-editor.md` §2.8): the composer offers emoji, the
 * reaction sheet offers emoji, and the image editor offers emoji, stickers,
 * text and shapes. A host that declares one tab renders no island at all, so
 * nothing ever ships greyed-out and unreachable.
 *
 * [GIF] is enumerated and deliberately declared by nobody. Sending a GIF as a
 * message needs a new `MessageType`, a Room version bump, a sync path and a
 * bubble renderer, plus the provider-privacy decision recorded in §2.8; placing
 * one *on a photo* is impossible while the output is a JPEG, since a flattened
 * animation is one frame and a worse sticker. Naming it here is what makes the
 * absence a decision rather than an oversight — see `docs/BACKLOG.md` §4.6.
 */
internal enum class PickerTab(
    /** The word on the island's active segment, and the handle a test grabs it by. */
    val label: String,
    /** What the search field asks for while this tab is the active one. */
    val searchHint: String,
) {
    EMOJI(label = "Emoji", searchHint = "Search emoji…"),
    STICKER(label = "Stickers", searchHint = "Search stickers…"),
    GIF(label = "GIFs", searchHint = "Search GIFs…"),
    TEXT(label = "Text", searchHint = "Search fonts…"),
    SHAPE(label = "Shapes", searchHint = "Search shapes…"),
}

/**
 * What a tab hands back when the user picks something from it.
 *
 * One sealed result rather than one callback per tab, so a host wires a single
 * lambda however many tabs it declares — and so adding a tab is a new subtype
 * here rather than a new parameter on every host.
 *
 * Only [Emoji] is declared so far, for the reason `RasterOp` shipped with four
 * of its seven ops in Phase 2: a selection's shape is a decision the phase that
 * designs the tab's UI has to make, and guessing now would ship a data shape
 * that phase would have to change anyway. The hierarchy is one file, so adding
 * a subtype stays local.
 */
internal sealed interface PickerSelection {
    /**
     * One emoji, and how large the long-press size drag made it — `1f` for an
     * ordinary tap. The size is meaningful only to hosts that can render an
     * emoji at a size; a reaction ignores it.
     */
    data class Emoji(val emoji: String, val size: Float) : PickerSelection
}
