package com.firestream.chat.domain.util

import java.util.Locale

/**
 * What a DOCUMENT message's file is, for the bubble's badge and for choosing a
 * preview. Classified from the mime type first and the file name's extension
 * second — a provider's type is often a generic octet-stream, a name rarely lies
 * about being a `.pdf`. Pure: no Android, so the bubble, previews and tests agree.
 */
enum class FileKind(val label: String) {
    PDF("PDF"),
    TEXT("TXT"),
    CODE("CODE"),
    WORD("DOC"),
    SPREADSHEET("XLS"),
    PRESENTATION("PPT"),
    ARCHIVE("ZIP"),
    AUDIO("AUDIO"),
    VIDEO("VIDEO"),
    IMAGE("IMG"),
    APK("APK"),
    EXECUTABLE("EXE"),
    OTHER("FILE");

    /** Whether the bubble can show this kind's first lines as text. */
    val hasTextPreview: Boolean get() = this == TEXT || this == CODE

    /**
     * Whether opening it hands control to something that can install or run
     * code — worth a confirm before the chooser.
     */
    val isRisky: Boolean get() = this == APK || this == EXECUTABLE

    companion object {
        private val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "log", "csv", "tsv", "rtf", "ini", "cfg", "conf", "srt", "vtt")
        private val CODE_EXTENSIONS = setOf(
            "json", "xml", "yaml", "yml", "toml", "html", "htm", "css", "js", "ts", "kt", "kts", "java", "py", "rb",
            "go", "rs", "c", "h", "cpp", "hpp", "cs", "swift", "php", "sql", "gradle", "properties",
        )
        private val SCRIPT_EXTENSIONS = setOf("sh", "bash", "bat", "cmd", "ps1", "exe", "msi", "jar", "vbs")
        private val WORD_EXTENSIONS = setOf("doc", "docx", "odt", "pages")
        private val SPREADSHEET_EXTENSIONS = setOf("xls", "xlsx", "ods", "numbers")
        private val PRESENTATION_EXTENSIONS = setOf("ppt", "pptx", "odp", "key")
        private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg")

        /** Classifies from [mimeType] and [fileName]; either may be null. */
        fun of(mimeType: String?, fileName: String?): FileKind {
            val ext = extensionOf(fileName)
            // A script or executable is risky whatever type its sender claimed.
            if (ext in SCRIPT_EXTENSIONS) return EXECUTABLE
            fromMime(mimeType?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim())?.let { return it }
            return fromExtension(ext)
        }

        /** The lower-case extension of [fileName], or `null` when it has none. */
        fun extensionOf(fileName: String?): String? =
            fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it.length <= 10 }

        private fun fromMime(mime: String?): FileKind? = when {
            mime == null || mime == "application/octet-stream" -> null
            mime == "application/pdf" -> PDF
            mime == "application/vnd.android.package-archive" -> APK
            mime.startsWith("image/") -> IMAGE
            mime.startsWith("video/") -> VIDEO
            mime.startsWith("audio/") -> AUDIO
            mime in CODE_MIMES -> CODE
            mime.startsWith("text/") -> TEXT
            "wordprocessing" in mime || mime == "application/msword" || "opendocument.text" in mime -> WORD
            "spreadsheet" in mime || mime == "application/vnd.ms-excel" -> SPREADSHEET
            "presentation" in mime || mime == "application/vnd.ms-powerpoint" -> PRESENTATION
            mime in ARCHIVE_MIMES -> ARCHIVE
            else -> null
        }

        private fun fromExtension(ext: String?): FileKind = when (ext) {
            null -> OTHER
            "pdf" -> PDF
            "apk", "apks", "xapk" -> APK
            in TEXT_EXTENSIONS -> TEXT
            in CODE_EXTENSIONS -> CODE
            in WORD_EXTENSIONS -> WORD
            in SPREADSHEET_EXTENSIONS -> SPREADSHEET
            in PRESENTATION_EXTENSIONS -> PRESENTATION
            in ARCHIVE_EXTENSIONS -> ARCHIVE
            in AUDIO_EXTENSIONS -> AUDIO
            in VIDEO_EXTENSIONS -> VIDEO
            in IMAGE_EXTENSIONS -> IMAGE
            else -> OTHER
        }

        private val CODE_MIMES = setOf(
            "application/json", "application/xml", "text/xml", "text/html", "text/css", "text/javascript",
            "application/javascript", "application/x-yaml", "text/x-yaml", "application/sql",
        )
        private val ARCHIVE_MIMES = setOf(
            "application/zip", "application/x-zip-compressed", "application/x-7z-compressed", "application/x-rar-compressed",
            "application/vnd.rar", "application/x-tar", "application/gzip", "application/x-gzip",
        )
    }
}

/** A byte count as people read it: `512 B`, `1.4 KB`, `23 MB`, `1.2 GB` (1024-based, one decimal under 10). */
fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val number = if (value < 10) String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0") else value.toLong().toString()
    return "$number ${units[unit]}"
}
