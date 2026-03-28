package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.Message

interface PollRepository {
    suspend fun sendPoll(chatId: String, question: String, options: List<String>, isMultipleChoice: Boolean, isAnonymous: Boolean): Result<Message>
    suspend fun votePoll(chatId: String, messageId: String, optionIds: List<String>): Result<Unit>
    suspend fun closePoll(chatId: String, messageId: String): Result<Unit>
}
