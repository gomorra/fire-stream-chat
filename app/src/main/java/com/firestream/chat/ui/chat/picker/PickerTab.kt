package com.firestream.chat.ui.chat.picker

import com.firestream.chat.domain.util.OverlayContent

/**
 * A tab the picker can offer, and the seam that keeps the picker honest about
 * what each of its hosts can actually do.
 *
 * The island of tabs in [PickerPanel] is **declared by the host**, not fixed
 * (`docs/plans/image-editor.md` §2.8). The composer offers emoji, the sticker
 * library and, in a build with a KLIPY key, GIFs. The reaction sheet and the
 * caption bar offer emoji. The image editor offers emoji, the bundled
 * stickers, text and shapes. A host that declares one tab renders no island at
 * all, so nothing ever ships greyed-out and unreachable.
 *
 * There are two sticker tabs, and no host declares both. [STICKER] is the
 * bundled vector pack, drawn onto a photo. [STICKER_LIBRARY] is the user's own
 * library, sent as a message.
 *
 * [GIF] is KLIPY's catalogue, and only the composer declares it. Its search
 * hint is the wording KLIPY's attribution rules require. A pick is a message,
 * and it leaves through the host's own callback, not as a [PickerSelection].
 * Placing a GIF on a photo stays impossible while the output is a JPEG, since
 * a flattened animation is one frame.
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
    STICKER_LIBRARY(label = "Stickers", searchHint = "Search stickers…"),
    GIF(label = "GIFs", searchHint = "Search KLIPY"),
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
 * Three subtypes, because there are three kinds of answer. An emoji is a
 * *character* with a size the long-press drag chose, which is what the composer
 * inserts into a message and the reaction sheet stores on one. A bundled
 * sticker, a text run and a shape are all *objects to place*, and the picker
 * hands those over as the domain type that already describes them. A library
 * sticker is a *message to send*, named by its id.
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

    /**
     * A sticker from the library, to send as a message.
     *
     * [packId] is the pack it was picked from, and null for a pick from
     * Recents. The repository decides whether the recipient gets to see it.
     */
    data class Sticker(val stickerId: String, val packId: String?) : PickerSelection
}
