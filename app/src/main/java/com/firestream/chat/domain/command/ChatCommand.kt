package com.firestream.chat.domain.command

import androidx.compose.runtime.Composable

interface ChatCommand {
    val id: String
    val displayName: String
    val description: String? get() = null
    val children: List<ChatCommand> get() = emptyList()

    val widget: ChatCommandWidget? get() = null
}

interface ChatCommandWidget {
    @Composable
    fun Render(
        chatId: String,
        composerText: String,
        onSend: (CommandPayload) -> Unit,
        onCancel: () -> Unit,
    )
}

sealed interface CommandPayload {
    data class Timer(val durationMs: Long, val caption: String?) : CommandPayload
}
