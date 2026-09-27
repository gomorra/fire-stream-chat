package com.firestream.chat.data.util

import android.content.Context
import com.firestream.chat.domain.util.FilePreview
import com.firestream.chat.domain.util.TextPreview
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * The text of a PDF's first pages, for the file bubble's excerpt. PdfBox parses
 * with temp-file buffering, so a large PDF does not land on the heap; only the
 * first [MAX_PAGES] are read. A scan (no text layer), an encrypted or a damaged
 * file answers `null` — the thumbnail still shows.
 */
internal object PdfText {

    const val MAX_PAGES = 3

    @Volatile
    private var resourcesLoaded = false

    fun extract(context: Context, file: File): FilePreview.Text? {
        if (!resourcesLoaded) {
            PDFBoxResourceLoader.init(context.applicationContext)
            resourcesLoaded = true
        }
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { document ->
            if (document.isEncrypted && !document.currentAccessPermission.canExtractContent()) return null
            val pages = document.numberOfPages
            if (pages == 0) return null
            val stripper = PDFTextStripper().apply {
                startPage = 1
                endPage = minOf(MAX_PAGES, pages)
            }
            return TextPreview.fromText(stripper.getText(document), moreFollows = pages > MAX_PAGES)
        }
    }
}
