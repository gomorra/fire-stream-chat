package com.firestream.chat.domain.util

object MentionParser {

    private val MENTION_REGEX = Regex("@(\\S+)")

    /**
     * Extracts user IDs from text containing @mentions.
     * Resolves display names to user IDs using the provided name→id map.
     * "@everyone" maps to the special "everyone" id.
     */
    fun extractMentions(
        text: String,
        displayNameToUserId: Map<String, String>
    ): List<String> {
        val mentions = mutableSetOf<String>()
        MENTION_REGEX.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            if (name.equals("everyone", ignoreCase = true)) {
                mentions.add("everyone")
            } else {
                // Try exact match first, then case-insensitive
                val userId = displayNameToUserId[name]
                    ?: displayNameToUserId.entries.firstOrNull {
                        it.key.equals(name, ignoreCase = true)
                    }?.value
                if (userId != null) {
                    mentions.add(userId)
                }
            }
        }
        return mentions.toList()
    }
}
