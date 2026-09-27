package com.firestream.chat.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileKindTest {

    @Test
    fun `the mime type classifies when it is specific`() {
        assertEquals(FileKind.PDF, FileKind.of("application/pdf", "whatever.bin"))
        assertEquals(FileKind.TEXT, FileKind.of("text/plain; charset=utf-8", null))
        assertEquals(FileKind.CODE, FileKind.of("application/json", null))
        assertEquals(FileKind.WORD, FileKind.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document", null))
        assertEquals(FileKind.SPREADSHEET, FileKind.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", null))
        assertEquals(FileKind.PRESENTATION, FileKind.of("application/vnd.openxmlformats-officedocument.presentationml.presentation", null))
        assertEquals(FileKind.AUDIO, FileKind.of("audio/mpeg", null))
        assertEquals(FileKind.ARCHIVE, FileKind.of("application/zip", null))
        assertEquals(FileKind.APK, FileKind.of("application/vnd.android.package-archive", null))
    }

    @Test
    fun `a generic octet-stream falls back to the file name's extension`() {
        assertEquals(FileKind.PDF, FileKind.of("application/octet-stream", "Scan.PDF"))
        assertEquals(FileKind.SPREADSHEET, FileKind.of("application/octet-stream", "budget.xlsx"))
        assertEquals(FileKind.TEXT, FileKind.of(null, "notes.md"))
        assertEquals(FileKind.CODE, FileKind.of(null, "Main.kt"))
        assertEquals(FileKind.OTHER, FileKind.of(null, "README"))
        assertEquals(FileKind.OTHER, FileKind.of(null, null))
    }

    @Test
    fun `a script is risky whatever type its sender claimed`() {
        assertEquals(FileKind.EXECUTABLE, FileKind.of("text/plain", "install.sh"))
        assertTrue(FileKind.of("text/plain", "install.sh").isRisky)
        assertTrue(FileKind.of(null, "app.apk").isRisky)
        assertFalse(FileKind.of("application/pdf", "a.pdf").isRisky)
    }

    @Test
    fun `only text and code files have a text preview`() {
        assertTrue(FileKind.TEXT.hasTextPreview)
        assertTrue(FileKind.CODE.hasTextPreview)
        assertFalse(FileKind.PDF.hasTextPreview)
        assertFalse(FileKind.WORD.hasTextPreview)
    }

    @Test
    fun `the badge shows a short extension, else the kind`() {
        assertEquals("DOCX", FileKind.badgeLabel(null, "Plan.docx"))
        assertEquals("MP3", FileKind.badgeLabel("audio/mpeg", "song.mp3"))
        assertEquals("PDF", FileKind.badgeLabel("application/pdf", "scan"))
        assertEquals("TXT", FileKind.badgeLabel("text/plain", "notes.markdown"))
        assertEquals("FILE", FileKind.badgeLabel(null, null))
    }

    @Test
    fun `sizes read as people read them`() {
        assertEquals("0 B", formatFileSize(0))
        assertEquals("1023 B", formatFileSize(1023))
        assertEquals("1 KB", formatFileSize(1024))
        assertEquals("1.4 MB", formatFileSize(1_468_006))
        assertEquals("23 MB", formatFileSize(23L * 1024 * 1024 + 5_000))
        assertEquals("1.2 GB", formatFileSize((1.2 * 1024 * 1024 * 1024).toLong()))
    }
}
