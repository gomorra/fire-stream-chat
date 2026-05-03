package com.firestream.chat.ui.chat.command

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.firestream.chat.domain.command.ChatCommand
import com.firestream.chat.domain.command.ChatCommandWidget
import com.firestream.chat.domain.command.CommandPayload
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Root `.timer` command. Has one child for V1: `.set`. The `.send` (resend my
 * most recent timer) child is parking-lot per the plan.
 */
@Singleton
class TimerCommand @Inject constructor(
    private val setSubcommand: TimerSetCommand,
) : ChatCommand {
    override val id: String = "timer"
    override val displayName: String = ".timer"
    override val description: String = "Set a timer that rings on both devices"
    override val children: List<ChatCommand> = listOf(setSubcommand)
    override val widget: ChatCommandWidget? = null
}

/**
 * Leaf `.timer.set` — mounts the picker widget. Step 4 swaps in the real
 * hh:mm:ss wheel; for now a placeholder so the framework compiles and the
 * end-to-end command dispatch can be exercised.
 */
@Singleton
class TimerSetCommand @Inject constructor(
    private val widgetImpl: TimerSetWidget,
) : ChatCommand {
    override val id: String = "set"
    override val displayName: String = ".set"
    override val description: String = "Pick a duration"
    override val children: List<ChatCommand> = emptyList()
    override val widget: ChatCommandWidget = widgetImpl
}

/**
 * Step-3 placeholder widget. Step 4 replaces this with the hh:mm:ss wheel
 * picker; the placeholder mounts so the framework wiring can be verified
 * end-to-end without the full UI yet.
 */
@Singleton
class TimerSetWidget @Inject constructor() : ChatCommandWidget {
    @Composable
    override fun Render(
        chatId: String,
        composerText: String,
        onSend: (CommandPayload) -> Unit,
        onCancel: () -> Unit,
    ) {
        Text(
            text = "Timer picker — coming in Step 4",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
