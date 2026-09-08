package com.firestream.chat.ui.search

import com.firestream.chat.domain.model.MessageFilterType
import com.firestream.chat.domain.model.MessageSearchFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The search pane's honesty rules, which are all in the formatting:
 * a `LIMIT`-capped count must not be shown as exact, and a date range must not
 * be widened to a whole month it does not actually cover.
 *
 * Timezone and locale are pinned, because both formatters read the defaults and
 * the UTC↔local day conversion only has a bug to catch east of UTC.
 */
class SearchFilterFormatTest {

    private lateinit var originalZone: TimeZone
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault()
        originalLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
        Locale.setDefault(originalLocale)
    }

    private fun localStartOfDay(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month, day, 0, 0, 0) }.timeInMillis

    private fun localEndOfDay(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, 0, 0, 0)
            add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis - 1

    // ── Result summary ───────────────────────────────────────────────────────

    @Test
    fun `an unfiltered search reads as plain results`() {
        assertEquals("5 results", searchResultsSummary(MessageSearchFilter.NONE, 5, atLimit = false))
        assertEquals("1 result", searchResultsSummary(MessageSearchFilter.NONE, 1, atLimit = false))
        assertEquals("0 results", searchResultsSummary(MessageSearchFilter.NONE, 0, atLimit = false))
    }

    @Test
    fun `the type chip names what was counted`() {
        val photos = MessageSearchFilter(type = MessageFilterType.PHOTOS)
        assertEquals("42 photos", searchResultsSummary(photos, 42, atLimit = false))
        assertEquals("1 photo", searchResultsSummary(photos, 1, atLimit = false))
        assertEquals(
            "3 voice messages",
            searchResultsSummary(MessageSearchFilter(type = MessageFilterType.VOICE), 3, atLimit = false),
        )
    }

    @Test
    fun `a capped count is rendered as a floor, never as an exact total`() {
        val photos = MessageSearchFilter(type = MessageFilterType.PHOTOS)

        assertEquals("200+ photos", searchResultsSummary(photos, 200, atLimit = true))
        // Even a count of one must stay plural when capped — "1+ photo" would
        // read as an exact one.
        assertEquals("1+ photos", searchResultsSummary(photos, 1, atLimit = true))
    }

    @Test
    fun `starred and the date range are appended as separate descriptors`() {
        val filter = MessageSearchFilter(
            type = MessageFilterType.PHOTOS,
            isStarred = true,
            fromMs = localStartOfDay(2026, Calendar.MARCH, 1),
            toMs = localEndOfDay(2026, Calendar.MARCH, 31),
        )

        assertEquals("42 photos · starred · Mar 2026", searchResultsSummary(filter, 42, atLimit = false))
    }

    @Test
    fun `starred alone still names the count`() {
        assertEquals(
            "3 results · starred",
            searchResultsSummary(MessageSearchFilter(isStarred = true), 3, atLimit = false),
        )
    }

    // ── Date range label ─────────────────────────────────────────────────────

    @Test
    fun `no range has no label`() {
        assertNull(dateRangeLabel(null, null))
    }

    @Test
    fun `a range covering exactly one whole month collapses to that month`() {
        val label = dateRangeLabel(
            localStartOfDay(2026, Calendar.MARCH, 1),
            localEndOfDay(2026, Calendar.MARCH, 31),
        )

        assertEquals("Mar 2026", label)
    }

    @Test
    fun `a partial month is not widened to the whole month`() {
        val label = dateRangeLabel(
            localStartOfDay(2026, Calendar.MARCH, 3),
            localEndOfDay(2026, Calendar.MARCH, 12),
        )

        assertEquals("3 Mar – 12 Mar", label)
    }

    @Test
    fun `a range within one year omits the year on both ends`() {
        val label = dateRangeLabel(
            localStartOfDay(2026, Calendar.MARCH, 3),
            localEndOfDay(2026, Calendar.APRIL, 4),
        )

        assertEquals("3 Mar – 4 Apr", label)
    }

    @Test
    fun `a range spanning years carries both years`() {
        val label = dateRangeLabel(
            localStartOfDay(2025, Calendar.DECEMBER, 20),
            localEndOfDay(2026, Calendar.JANUARY, 4),
        )

        assertEquals("20 Dec 2025 – 4 Jan 2026", label)
    }

    @Test
    fun `an open-ended range says which end is open`() {
        assertEquals(
            "From 3 Mar 2026",
            dateRangeLabel(localStartOfDay(2026, Calendar.MARCH, 3), null),
        )
        assertEquals(
            "Until 3 Mar 2026",
            dateRangeLabel(null, localEndOfDay(2026, Calendar.MARCH, 3)),
        )
    }

    // ── Picker boundary conversion ───────────────────────────────────────────

    @Test
    fun `a picked day becomes the local day, not the UTC one`() {
        // What DateRangePicker reports for 3 March 2026: UTC midnight.
        val utcMidnight = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.MARCH, 3, 0, 0, 0)
        }.timeInMillis

        assertEquals(localStartOfDay(2026, Calendar.MARCH, 3), utcDayToLocalStart(utcMidnight))
        assertEquals(localEndOfDay(2026, Calendar.MARCH, 3), utcDayToLocalEnd(utcMidnight))
    }

    @Test
    fun `a message late on the last selected day is inside the range`() {
        // The bug this conversion exists for: 23:00 local on the final day sits
        // after a UTC-anchored end boundary in any zone east of UTC.
        val utcMidnight = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.MARCH, 3, 0, 0, 0)
        }.timeInMillis
        val lateLocal = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.MARCH, 3, 23, 0, 0)
        }.timeInMillis

        assert(lateLocal <= utcDayToLocalEnd(utcMidnight))
    }

    @Test
    fun `reopening the picker restores the day that was chosen`() {
        val chosen = localStartOfDay(2026, Calendar.MARCH, 3)

        assertEquals(chosen, utcDayToLocalStart(localDayToUtcDay(chosen)))
    }

    @Test
    fun `a DST-shortened day still ends just before the next one starts`() {
        // 29 March 2026 is 23 hours long in Europe/Berlin; a naive +24h would
        // overshoot into the following day.
        val end = utcDayToLocalEnd(
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(2026, Calendar.MARCH, 29, 0, 0, 0)
            }.timeInMillis
        )

        assertEquals(localStartOfDay(2026, Calendar.MARCH, 30) - 1, end)
    }
}
