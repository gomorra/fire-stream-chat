package com.firestream.chat.ui.chat

import com.firestream.chat.domain.command.ChatCommand
import com.firestream.chat.domain.command.ChatCommandWidget
import com.firestream.chat.domain.command.CommandPath

internal data class CommandsState(
    val isPaletteOpen: Boolean = false,
    val currentPath: CommandPath = CommandPath.ROOT,
    val candidates: List<ChatCommand> = emptyList(),
    val activeWidget: ChatCommandWidget? = null,
    val filter: String = "",
    val exactAlarmBannerVisible: Boolean = false,
)
