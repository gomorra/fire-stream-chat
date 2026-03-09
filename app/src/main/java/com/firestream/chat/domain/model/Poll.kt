package com.firestream.chat.domain.model

data class Poll(
    val question: String = "",
    val options: List<PollOption> = emptyList(),
    val isMultipleChoice: Boolean = false,
    val isAnonymous: Boolean = false,
    val isClosed: Boolean = false
)

data class PollOption(
    val id: String = "",
    val text: String = "",
    val voterIds: List<String> = emptyList()
)
