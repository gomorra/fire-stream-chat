package com.firestream.chat.data.util

import android.content.Context

data class ChangelogEntry(val boldLabel: String?, val body: String)
data class ChangelogSection(val heading: String, val entries: List<ChangelogEntry>)
data class ChangelogVersion(val version: String, val date: String?, val sections: List<ChangelogSection>)

object ChangelogParser {

    private val versionHeader = Regex("""^##\s+\[([^\]]+)]\s*(?:[—-]\s*(\S+))?\s*$""")
    private val sectionHeader = Regex("""^###\s+(.+?)\s*$""")
    private val boldEntry = Regex("""^-\s+\*\*(.+?)\*\*\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val plainEntry = Regex("""^-\s+(.+)$""")
    // Matches trailing commit-hash references like (`abc1234`) or (`abc1234`, `def5678`)
    private val trailingHashes = Regex("""\s*\(`[0-9a-f]{6,}`(?:,\s*`[0-9a-f]{6,}`)*\)\s*$""")

    fun parse(text: String): List<ChangelogVersion> {
        val versions = mutableListOf<ChangelogVersion>()
        var currentVersion: String? = null
        var currentDate: String? = null
        var currentSections = mutableListOf<ChangelogSection>()
        var currentSectionHeading: String? = null
        var currentEntries = mutableListOf<ChangelogEntry>()
        var currentBoldLabel: String? = null
        var currentBody: StringBuilder? = null
        var pendingBlank = false

        fun finalizeEntry() {
            val body = currentBody ?: return
            val stripped = body.toString().replace(trailingHashes, "").trim()
            currentEntries.add(ChangelogEntry(currentBoldLabel, stripped))
            currentBoldLabel = null
            currentBody = null
            pendingBlank = false
        }

        fun finalizeSection() {
            finalizeEntry()
            val heading = currentSectionHeading ?: return
            if (currentEntries.isNotEmpty()) {
                currentSections.add(ChangelogSection(heading, currentEntries.toList()))
            }
            currentSectionHeading = null
            currentEntries = mutableListOf()
        }

        fun finalizeVersion() {
            finalizeSection()
            val version = currentVersion ?: return
            versions.add(ChangelogVersion(version, currentDate, currentSections.toList()))
            currentVersion = null
            currentDate = null
            currentSections = mutableListOf()
        }

        for (line in text.lineSequence()) {
            val versionMatch = versionHeader.matchEntire(line)
            if (versionMatch != null) {
                finalizeVersion()
                currentVersion = versionMatch.groupValues[1]
                currentDate = versionMatch.groupValues[2].takeIf { it.isNotEmpty() }
                continue
            }

            if (currentVersion == null) continue

            val sectionMatch = sectionHeader.matchEntire(line)
            if (sectionMatch != null) {
                finalizeSection()
                currentSectionHeading = sectionMatch.groupValues[1]
                continue
            }

            if (currentSectionHeading == null) continue

            val boldMatch = boldEntry.matchEntire(line)
            if (boldMatch != null) {
                finalizeEntry()
                currentBoldLabel = boldMatch.groupValues[1]
                currentBody = StringBuilder(boldMatch.groupValues[2].trim())
                pendingBlank = false
                continue
            }

            val plainMatch = plainEntry.matchEntire(line)
            if (plainMatch != null) {
                finalizeEntry()
                currentBoldLabel = null
                currentBody = StringBuilder(plainMatch.groupValues[1].trim())
                pendingBlank = false
                continue
            }

            // Continuation or blank line within an entry
            if (currentBody != null) {
                if (line.isBlank()) {
                    pendingBlank = true
                } else {
                    if (pendingBlank) {
                        currentBody!!.append("\n\n")
                        pendingBlank = false
                    } else if (currentBody!!.isNotEmpty()) {
                        currentBody!!.append(" ")
                    }
                    currentBody!!.append(line.trim())
                }
            }
        }

        finalizeVersion()
        return versions
    }

    fun loadFromAssets(context: Context): String =
        context.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }

    fun selectCurrentVersion(versions: List<ChangelogVersion>, versionName: String): ChangelogVersion? {
        if (versions.isEmpty()) return null

        val isExact = versionName.matches(Regex("""\d+\.\d+\.\d+"""))
        return if (isExact) {
            versions.firstOrNull { it.version == versionName }
                ?: versions.firstOrNull { it.version == "Unreleased" }
                ?: versions.firstOrNull()
        } else {
            // Dev build (e.g. "1.6.4-dev+abc1234") — prefer Unreleased if it has content
            val unreleased = versions.firstOrNull { it.version == "Unreleased" }
            if (unreleased != null && unreleased.sections.isNotEmpty()) {
                unreleased
            } else {
                versions.firstOrNull { it.date != null }
            }
        }
    }
}
