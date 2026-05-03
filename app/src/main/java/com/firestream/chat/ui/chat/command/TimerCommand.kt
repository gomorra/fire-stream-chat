package com.firestream.chat.ui.chat.command

import com.firestream.chat.domain.command.ChatCommand
import com.firestream.chat.domain.command.ChatCommandWidget
import com.firestream.chat.ui.chat.widget.TimerSetWidget
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

/** Leaf `.timer.set` — mounts the hh:mm:ss wheel picker. */
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
