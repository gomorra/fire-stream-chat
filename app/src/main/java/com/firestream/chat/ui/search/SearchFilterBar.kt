package com.firestream.chat.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.MessageFilterType
import com.firestream.chat.domain.model.MessageSearchFilter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The prefilter chips under the search box, which turn the search pane into a
 * browser ("show me the photos") rather than only a text matcher. Shared by
 * in-chat and global search.
 *
 * One horizontally scrollable row with no overflow "…" chip: an overflow chip
 * cannot honestly show selection state — it either hides the active filter or
 * reorders chips under the thumb. Both hosts (`ChatScreen`, `GlobalSearchScreen`)
 * are their own NavHost destinations rather than pages in `MainScreen`'s pager,
 * so the [LazyRow] has no gesture conflict to worry about — do not host this row
 * inside a pager page.
 *
 * The type chips are single-select (multi-select would read as AND where the
 * user means OR); Starred and Date are independent toggles on their own axes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchFilterChipRow(
    filter: MessageSearchFilter,
    onFilterChange: (MessageSearchFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        items(MessageFilterType.entries.toList(), key = { it.name }) { type ->
            SearchFilterChip(
                selected = filter.type == type,
                label = type.chipLabel,
                icon = type.chipIcon,
                // Re-tapping the active type chip clears it: with single-select
                // there is no other way back to "all types".
                onClick = { onFilterChange(filter.copy(type = type.takeIf { filter.type != type })) },
            )
        }
        item(key = "starred") {
            SearchFilterChip(
                selected = filter.isStarred,
                label = "Starred",
                icon = Icons.Default.Star,
                onClick = { onFilterChange(filter.copy(isStarred = !filter.isStarred)) },
            )
        }
        item(key = "date") {
            val rangeLabel = dateRangeLabel(filter.fromMs, filter.toMs)
            SearchFilterChip(
                selected = rangeLabel != null,
                // The chip carries the chosen range so it stays self-describing
                // once it has scrolled away from the summary line.
                label = rangeLabel ?: "Date",
                icon = Icons.Default.DateRange,
                onClick = { showDatePicker = true },
                // A caret rather than a clear button: the chip is 32dp tall, and
                // a nested 48dp touch target inside it would swallow taps meant
                // for the chip. Clearing the range is the picker's own Clear.
                showsPicker = true,
            )
        }
    }

    if (showDatePicker) {
        SearchDateRangeDialog(
            filter = filter,
            onDismiss = { showDatePicker = false },
            onConfirm = { from, to ->
                onFilterChange(filter.copy(fromMs = from, toMs = to))
                showDatePicker = false
            },
        )
    }
}

@Composable
private fun SearchFilterChip(
    selected: Boolean,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    showsPicker: Boolean = false,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        },
        trailingIcon = if (!showsPicker) null else {
            {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchDateRangeDialog(
    filter: MessageSearchFilter,
    onDismiss: () -> Unit,
    onConfirm: (from: Long?, to: Long?) -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = filter.fromMs?.let(::localDayToUtcDay),
        initialSelectedEndDateMillis = filter.toMs?.let(::localDayToUtcDay),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        state.selectedStartDateMillis?.let(::utcDayToLocalStart),
                        state.selectedEndDateMillis?.let(::utcDayToLocalEnd),
                    )
                },
                enabled = state.selectedStartDateMillis != null,
            ) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = { onConfirm(null, null) }) { Text("Clear") }
        },
    ) {
        DateRangePicker(state = state, showModeToggle = false)
    }
}

/** The active-filter summary and its clear affordance, shown above the results. */
@Composable
internal fun SearchResultsSummary(
    summary: String,
    showClear: Boolean,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        if (showClear) {
            IconButton(onClick = onClearFilters, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear filters",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private val MessageFilterType.chipLabel: String
    get() = when (this) {
        MessageFilterType.PHOTOS -> "Photos"
        MessageFilterType.VIDEOS -> "Videos"
        MessageFilterType.LINKS -> "Links"
        MessageFilterType.DOCS -> "Docs"
        MessageFilterType.VOICE -> "Voice"
    }

private val MessageFilterType.chipIcon: ImageVector
    get() = when (this) {
        MessageFilterType.PHOTOS -> Icons.Default.Image
        MessageFilterType.VIDEOS -> Icons.Default.Videocam
        MessageFilterType.LINKS -> Icons.Default.Link
        MessageFilterType.DOCS -> Icons.Default.Description
        MessageFilterType.VOICE -> Icons.Default.Mic
    }

// ── Formatting (pure; unit-tested in SearchFilterFormatTest) ────────────────

/**
 * The line above the results: `42 photos · Mar 2026`.
 *
 * [atLimit] is not cosmetic. The query is `LIMIT`-capped, so a full page means
 * "at least this many", and rendering a bare `200` would claim 200 photos exist
 * when there may be 2,000. A capped count is rendered `200+`.
 */
internal fun searchResultsSummary(
    filter: MessageSearchFilter,
    resultCount: Int,
    atLimit: Boolean,
): String {
    val noun = filter.type.resultNoun(singular = resultCount == 1 && !atLimit)
    val count = if (atLimit) "$resultCount+" else "$resultCount"
    val parts = mutableListOf("$count $noun")
    if (filter.isStarred) parts += "starred"
    dateRangeLabel(filter.fromMs, filter.toMs)?.let { parts += it }
    return parts.joinToString(" · ")
}

private fun MessageFilterType?.resultNoun(singular: Boolean): String = when (this) {
    MessageFilterType.PHOTOS -> if (singular) "photo" else "photos"
    MessageFilterType.VIDEOS -> if (singular) "video" else "videos"
    MessageFilterType.LINKS -> if (singular) "link" else "links"
    MessageFilterType.DOCS -> if (singular) "document" else "documents"
    MessageFilterType.VOICE -> if (singular) "voice message" else "voice messages"
    null -> if (singular) "result" else "results"
}

/**
 * A human label for the chosen range, or null when no range is set.
 *
 * Collapses to `Mar 2026` only when the range actually spans that whole month —
 * showing "Mar 2026" for 3–12 March would overstate what is being searched, and
 * the chip is the only place an off-screen filter is visible.
 */
internal fun dateRangeLabel(fromMs: Long?, toMs: Long?): String? {
    // The common case by far — searchResultsSummary calls this on every
    // keystroke, and building three pattern-compiling formatters only to
    // discard them is the kind of waste a text field notices.
    if (fromMs == null && toMs == null) return null
    return when {
        fromMs != null && toMs != null -> when {
            spansWholeMonth(fromMs, toMs) -> monthYear.format(fromMs)
            sameYear(fromMs, toMs) -> "${dayMonth.format(fromMs)} – ${dayMonth.format(toMs)}"
            else -> "${dayMonthYear.format(fromMs)} – ${dayMonthYear.format(toMs)}"
        }
        fromMs != null -> "From ${dayMonthYear.format(fromMs)}"
        toMs != null -> "Until ${dayMonthYear.format(toMs)}"
        else -> null
    }
}

// File-level, like SearchResults.kt's resultDateFormat: SimpleDateFormat is not
// thread-safe, but every caller here is composition or a unit test, both
// single-threaded.
private val dayMonth = SimpleDateFormat("d MMM", Locale.getDefault())
private val dayMonthYear = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
private val monthYear = SimpleDateFormat("MMM yyyy", Locale.getDefault())

private fun calendarAt(millis: Long) = Calendar.getInstance().apply { timeInMillis = millis }

private fun sameYear(a: Long, b: Long) =
    calendarAt(a).get(Calendar.YEAR) == calendarAt(b).get(Calendar.YEAR)

private fun spansWholeMonth(fromMs: Long, toMs: Long): Boolean {
    val from = calendarAt(fromMs)
    val to = calendarAt(toMs)
    return from.get(Calendar.YEAR) == to.get(Calendar.YEAR) &&
        from.get(Calendar.MONTH) == to.get(Calendar.MONTH) &&
        fromMs == startOfDay(from.get(Calendar.YEAR), from.get(Calendar.MONTH), 1) &&
        toMs == endOfDay(
            to.get(Calendar.YEAR),
            to.get(Calendar.MONTH),
            to.getActualMaximum(Calendar.DAY_OF_MONTH),
        )
}

private fun startOfDay(year: Int, month: Int, day: Int): Long =
    Calendar.getInstance().apply {
        clear()
        set(year, month, day, 0, 0, 0)
    }.timeInMillis

// Via "start of the next day, minus a millisecond" rather than +24h, so a day
// that DST makes 23 or 25 hours long still ends where it should.
private fun endOfDay(year: Int, month: Int, day: Int): Long =
    Calendar.getInstance().apply {
        clear()
        set(year, month, day, 0, 0, 0)
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis - 1

/**
 * [DateRangePicker] speaks UTC midnights while the messages being filtered
 * carry local wall-clock timestamps, so the two boundaries are converted
 * explicitly. Without this a message sent at 23:00 local on the last selected
 * day falls outside a UTC-anchored range in any timezone east of UTC.
 */
internal fun utcDayToLocalStart(utcMillis: Long): Long {
    val utc = utcCalendarAt(utcMillis)
    return startOfDay(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
}

internal fun utcDayToLocalEnd(utcMillis: Long): Long {
    val utc = utcCalendarAt(utcMillis)
    return endOfDay(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
}

/** The inverse, so reopening the picker restores the days the user picked. */
internal fun localDayToUtcDay(localMillis: Long): Long {
    val local = calendarAt(localMillis)
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}

private fun utcCalendarAt(millis: Long) =
    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
