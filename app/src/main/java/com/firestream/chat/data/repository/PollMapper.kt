package com.firestream.chat.data.repository

import com.firestream.chat.domain.model.Poll
import com.firestream.chat.domain.model.PollOption

@Suppress("UNCHECKED_CAST")
internal fun parsePollFromFirestore(data: Map<String, Any?>): Poll {
    val options = (data["options"] as? List<Map<String, Any?>>)?.map { opt ->
        PollOption(
            id = opt["id"] as? String ?: "",
            text = opt["text"] as? String ?: "",
            voterIds = (opt["voterIds"] as? List<String>) ?: emptyList()
        )
    } ?: emptyList()

    return Poll(
        question = data["question"] as? String ?: "",
        options = options,
        isMultipleChoice = data["isMultipleChoice"] as? Boolean ?: false,
        isAnonymous = data["isAnonymous"] as? Boolean ?: false,
        isClosed = data["isClosed"] as? Boolean ?: false
    )
}

internal fun buildPollFirestoreMap(poll: Poll): Map<String, Any?> {
    return mapOf(
        "question" to poll.question,
        "isMultipleChoice" to poll.isMultipleChoice,
        "isAnonymous" to poll.isAnonymous,
        "isClosed" to poll.isClosed,
        "options" to poll.options.map { option ->
            mapOf(
                "id" to option.id,
                "text" to option.text,
                "voterIds" to option.voterIds
            )
        }
    )
}
