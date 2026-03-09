package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.util.MentionParser
import javax.inject.Inject

class ParseMentionsUseCase @Inject constructor() {
    operator fun invoke(
        text: String,
        displayNameToUserId: Map<String, String>
    ): List<String> {
        return MentionParser.extractMentions(text, displayNameToUserId)
    }
}
