package com.firestream.chat.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * UI-layer mention rendering. Pure mention parsing (`extractMentions`) lives in
 * `domain/util/MentionParser.kt` — this file holds the Compose-specific text
 * highlighting that would otherwise leak Android/Compose imports into the
 * domain layer.
 */
object MentionFormatter {

    private val MENTION_REGEX = Regex("@(\\S+)")

    /**
     * Creates an AnnotatedString with @mentions highlighted in the given color.
     * Mentions of the current user are bolded.
     */
    fun formatMentionText(
        text: String,
        mentions: List<String>,
        currentUserId: String,
        highlightColor: Color,
        userIdToDisplayName: Map<String, String> = emptyMap()
    ): AnnotatedString {
        if (mentions.isEmpty()) return AnnotatedString(text)

        // Build a set of display names that are mentioned
        val mentionedNames = buildSet {
            mentions.forEach { userId ->
                if (userId == "everyone") {
                    add("everyone")
                } else {
                    userIdToDisplayName[userId]?.let { add(it) }
                }
            }
        }
        if (mentionedNames.isEmpty()) return AnnotatedString(text)

        return buildAnnotatedString {
            var lastIndex = 0
            MENTION_REGEX.findAll(text).forEach { match ->
                val name = match.groupValues[1]
                val isMentioned = mentionedNames.any { it.equals(name, ignoreCase = true) }
                if (isMentioned) {
                    // Append text before this mention
                    append(text.substring(lastIndex, match.range.first))
                    // Highlight the mention
                    val isCurrentUser = name.equals("everyone", ignoreCase = true) ||
                            userIdToDisplayName[currentUserId]?.equals(name, ignoreCase = true) == true
                    withStyle(
                        SpanStyle(
                            color = highlightColor,
                            fontWeight = if (isCurrentUser) FontWeight.Bold else FontWeight.SemiBold
                        )
                    ) {
                        append(match.value)
                    }
                    lastIndex = match.range.last + 1
                }
            }
            // Append remaining text
            if (lastIndex < text.length) {
                append(text.substring(lastIndex))
            }
        }
    }
}
