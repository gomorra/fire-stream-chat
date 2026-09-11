package com.firestream.chat.ui.chat.picker

import com.firestream.chat.domain.util.OverlayContent

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
    /**
     * What the search field asks for while this tab is the active one, or
     * **null when there is nothing on the tab to search** — which is what
     * [PickerPanel] reads to decide whether to offer a search field at all.
     *
     * A tab is searchable only if it has a list a query can shorten: emoji
     * filter a few thousand names, stickers filter a pack, and a GIF query
     * would go to a provider. Text and Shapes have no such list — the text tab
     * is a field you type the overlay into, and the shape tab is five buttons
     * that all fit on screen. Offering a field there would be worse than
     * offering nothing: it looks live, it accepts what you type, and it cannot
     * do anything with it. Same rule as the island a one-tab host does not
     * draw — nothing ships unreachable, and nothing ships inert.
     */
    val searchHint: String?,
) {
    EMOJI(label = "Emoji", searchHint = "Search emoji…"),
    STICKER(label = "Stickers", searchHint = "Search stickers…"),
    GIF(label = "GIFs", searchHint = "Search GIFs…"),
    TEXT(label = "Text", searchHint = null),
    SHAPE(label = "Shapes", searchHint = null),
}

/**
 * What a tab hands back when the user picks something from it.
 *
 * One sealed result rather than one callback per tab, so a host wires a single
 * lambda however many tabs it declares — and so adding a tab is a new subtype
 * here rather than a new parameter on every host.
 *
 * Two subtypes, not five, because there are only two kinds of answer. An emoji
 * is a *character* with a size the long-press drag chose, which is what the
 * composer inserts into a message and the reaction sheet stores on one; a
 * sticker, a text run and a shape are all *objects to place*, and the picker
 * hands those over as the domain type that already describes them rather than
 * re-declaring their fields here and forcing every host to map between two
 * identical shapes.
 */
internal sealed interface PickerSelection {
    /**
     * One emoji, and how large the long-press size drag made it — `1f` for an
     * ordinary tap. The size is meaningful only to hosts that can render an
     * emoji at a size; a reaction ignores it, and so does the image editor,
     * which has a scale handle of its own.
     */
    data class Emoji(val emoji: String, val size: Float) : PickerSelection

    /**
     * Something to place on a photo, ready to become an
     * `com.firestream.chat.domain.util.ImageOverlay`.
     *
     * Only the image editor declares the tabs that produce these, and only the
     * image editor can do anything with one — placing an object is meaningless
     * to a text field or a reaction.
     */
    data class Overlay(val content: OverlayContent) : PickerSelection
}
