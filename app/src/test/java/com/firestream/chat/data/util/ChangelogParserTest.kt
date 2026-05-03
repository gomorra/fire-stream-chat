package com.firestream.chat.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogParserTest {

    // Minimal two-version fixture modelled on the real CHANGELOG.md
    private val twoVersionFixture = """
        # Changelog

        ## [Unreleased]

        ### Added
        - **New thing.** Some description of new thing.

        ## [1.6.3] — 2026-05-01

        ### Fixed
        - **Cold-start spinner.** Tapping an image showed the spinner. Fixed via synchronous check. (`f7783d1`)

        ### Added
        - **HD label.** Images sent with full quality now show an HD pill. (`dbc17ff`, `abc1234`)

        ## [1.6.2] — 2026-04-30

        ### Fixed
        - Something was wrong without bold formatting.
    """.trimIndent()

    @Test
    fun `parse returns correct number of versions`() {
        val result = ChangelogParser.parse(twoVersionFixture)
        assertEquals(3, result.size)
    }

    @Test
    fun `parse captures version and date correctly`() {
        val result = ChangelogParser.parse(twoVersionFixture)
        assertEquals("Unreleased", result[0].version)
        assertNull(result[0].date)
        assertEquals("1.6.3", result[1].version)
        assertEquals("2026-05-01", result[1].date)
        assertEquals("1.6.2", result[2].version)
        assertEquals("2026-04-30", result[2].date)
    }

    @Test
    fun `parse splits entries into correct sections`() {
        val result = ChangelogParser.parse(twoVersionFixture)
        val v163 = result[1]
        assertEquals(2, v163.sections.size)
        assertEquals("Fixed", v163.sections[0].heading)
        assertEquals("Added", v163.sections[1].heading)
    }

    @Test
    fun `parse extracts bold label with period preserved inside`() {
        val result = ChangelogParser.parse(twoVersionFixture)
        val entry = result[1].sections[0].entries[0]
        assertEquals("Cold-start spinner.", entry.boldLabel)
        assertEquals("Tapping an image showed the spinner. Fixed via synchronous check.", entry.body)
    }

    @Test
    fun `parse strips single trailing commit hash`() {
        val result = ChangelogParser.parse(twoVersionFixture)
        val body = result[1].sections[0].entries[0].body
        assertTrue("body should not contain backtick hash ref", !body.contains("`f7783d1`"))
    }

    @Test
    fun `parse strips multiple trailing commit hashes`() {
        val result = ChangelogParser.parse(twoVersionFixture)
        val body = result[1].sections[1].entries[0].body
        assertTrue("body should not contain backtick hash refs", !body.contains("`dbc17ff`"))
        assertTrue("body should not contain backtick hash refs", !body.contains("`abc1234`"))
    }

    @Test
    fun `parse handles plain entry without bold`() {
        val result = ChangelogParser.parse(twoVersionFixture)
        val entry = result[2].sections[0].entries[0]
        assertNull(entry.boldLabel)
        assertEquals("Something was wrong without bold formatting.", entry.body)
    }

    @Test
    fun `parse handles unknown section heading verbatim`() {
        val input = """
            ## [1.0.0] — 2026-01-01

            ### Foo
            - **Widget.** Widgetry occurred.
        """.trimIndent()
        val result = ChangelogParser.parse(input)
        assertEquals("Foo", result[0].sections[0].heading)
    }

    @Test
    fun `parse merges continuation lines into entry body`() {
        val input = """
            ## [1.0.0] — 2026-01-01

            ### Fixed
            - **First line.** Start of body.
            This is a continuation of the same entry.
            Still the same entry.
        """.trimIndent()
        val result = ChangelogParser.parse(input)
        val body = result[0].sections[0].entries[0].body
        assertTrue("continuation should be merged", body.contains("Start of body."))
        assertTrue("continuation should be merged", body.contains("continuation of the same entry"))
    }

    @Test
    fun `parse handles multi-paragraph entry with blank line`() {
        val input = """
            ## [1.0.0] — 2026-01-01

            ### Fixed
            - **Para.** First paragraph.

            Second paragraph.
        """.trimIndent()
        val result = ChangelogParser.parse(input)
        val body = result[0].sections[0].entries[0].body
        assertTrue("body should contain both paragraphs", body.contains("First paragraph."))
        assertTrue("body should contain double newline", body.contains("\n\n"))
        assertTrue("body should contain second paragraph", body.contains("Second paragraph."))
    }

    @Test
    fun `parse returns empty list for empty input`() {
        val result = ChangelogParser.parse("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse returns empty list for text with no version headers`() {
        val result = ChangelogParser.parse("# Changelog\nSome intro text.")
        assertTrue(result.isEmpty())
    }

    // --- selectCurrentVersion ---

    @Test
    fun `selectCurrentVersion returns exact version match`() {
        val versions = ChangelogParser.parse(twoVersionFixture)
        val result = ChangelogParser.selectCurrentVersion(versions, "1.6.3")
        assertNotNull(result)
        assertEquals("1.6.3", result!!.version)
    }

    @Test
    fun `selectCurrentVersion falls back to Unreleased for dev build`() {
        val versions = ChangelogParser.parse(twoVersionFixture)
        val result = ChangelogParser.selectCurrentVersion(versions, "1.6.3-dev+abc1234")
        assertNotNull(result)
        assertEquals("Unreleased", result!!.version)
    }

    @Test
    fun `selectCurrentVersion falls back to first dated section when Unreleased is empty`() {
        val noUnreleased = """
            ## [1.6.3] — 2026-05-01

            ### Fixed
            - **Thing.** Fixed it.
        """.trimIndent()
        val versions = ChangelogParser.parse(noUnreleased)
        val result = ChangelogParser.selectCurrentVersion(versions, "1.6.3-dev+abc1234")
        assertNotNull(result)
        assertEquals("1.6.3", result!!.version)
    }

    @Test
    fun `selectCurrentVersion returns null for empty versions list`() {
        val result = ChangelogParser.selectCurrentVersion(emptyList(), "1.0.0")
        assertNull(result)
    }

    @Test
    fun `selectCurrentVersion falls back to Unreleased when exact version missing`() {
        val versions = ChangelogParser.parse(twoVersionFixture)
        val result = ChangelogParser.selectCurrentVersion(versions, "9.9.9")
        assertNotNull(result)
        assertEquals("Unreleased", result!!.version)
    }

    @Test
    fun `selectCurrentVersion falls back to first entry when exact version and Unreleased both missing`() {
        val noUnreleased = """
            ## [1.6.3] — 2026-05-01

            ### Fixed
            - **Thing.** Fixed it.
        """.trimIndent()
        val versions = ChangelogParser.parse(noUnreleased)
        val result = ChangelogParser.selectCurrentVersion(versions, "9.9.9")
        assertNotNull(result)
        assertEquals("1.6.3", result!!.version)
    }
}
