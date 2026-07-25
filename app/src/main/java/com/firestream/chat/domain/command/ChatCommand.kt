package com.firestream.chat.domain.command

import androidx.compose.runtime.Composable
import com.firestream.chat.domain.model.TimerAlarmSound
import com.firestream.chat.domain.model.TimerAlarmStyle

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
    data class Timer(
        val durationMs: Long,
        val caption: String?,
        val style: TimerAlarmStyle = TimerAlarmStyle.DEFAULT,
        val sound: TimerAlarmSound = TimerAlarmSound.DEFAULT,
    ) : CommandPayload
}
