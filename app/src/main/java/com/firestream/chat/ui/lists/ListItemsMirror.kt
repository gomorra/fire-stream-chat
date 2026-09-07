package com.firestream.chat.ui.lists

import com.firestream.chat.domain.model.ListItem

/**
 * Result of reconciling the observed list against the detail screen's local drag mirror.
 *
 * @param items what the list should render now.
 * @param pendingOrderIds the still-unconfirmed local order, or null once the mirror is
 *   back in sync with the observed list.
 */
data class ListItemsMirror(
    val items: List<ListItem>,
    val pendingOrderIds: List<String>?
)

/**
 * Merges a freshly observed item list into the drag mirror.
 *
 * The detail screen keeps a local copy of the items so a drag can move rows before the
 * reorder round-trips. That copy must never win over a real content change: membership
 * changes (add, swipe-remove, clear-checked) and field changes (text, checked) always
 * take the observed list. The local order is only preserved while our own reorder write
 * is still in flight *and* the observed membership is unchanged — an order-only
 * difference we are already expecting to disappear.
 */
fun reconcileListItems(
    observed: List<ListItem>,
    pendingOrderIds: List<String>?
): ListItemsMirror {
    if (pendingOrderIds != null) {
        val observedIds = observed.map { it.id }
        if (observedIds.toSet() == pendingOrderIds.toSet() && observedIds != pendingOrderIds) {
            // Same items, different order: our reorder has not echoed back yet. Keep the
            // user's order but adopt the observed field values so a concurrent
            // check/rename is not lost.
            val byId = observed.associateBy { it.id }
            return ListItemsMirror(
                items = pendingOrderIds.mapNotNull { byId[it] },
                pendingOrderIds = pendingOrderIds
            )
        }
    }
    return ListItemsMirror(items = observed, pendingOrderIds = null)
}
