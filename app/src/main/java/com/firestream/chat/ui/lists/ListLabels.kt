package com.firestream.chat.ui.lists

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.ui.graphics.vector.ImageVector
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListType

/**
 * How a list describes itself wherever it is drawn — the Lists tab, the
 * shared-lists screen and the share panel all say the same thing about the same
 * list, because they ask here.
 */

/** The icon standing in for the list's kind. */
internal val ListData.typeIcon: ImageVector
    get() = when (type) {
        ListType.CHECKLIST -> Icons.Default.Checklist
        ListType.SHOPPING -> Icons.Default.ShoppingCart
        ListType.GENERIC -> Icons.AutoMirrored.Filled.List
    }

/**
 * "3/7 checked" for a checklist or a shopping list, "7 items" for a generic one.
 *
 * Reads the denormalized counts rather than `items.size`: items live in a
 * subcollection and are not hydrated on the list-index streams, so counting the
 * loaded ones reports zero for every list (the 1.19.x "0 items" bug).
 */
internal val ListData.summaryLine: String
    get() = when (type) {
        ListType.CHECKLIST, ListType.SHOPPING -> "$checkedCount/$itemCount checked"
        ListType.GENERIC -> "$itemCount item${if (itemCount != 1) "s" else ""}"
    }
