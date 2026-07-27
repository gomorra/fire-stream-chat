package com.firestream.chat.ui.chat

import com.firestream.chat.domain.command.ChatCommandWidget
import com.firestream.chat.domain.command.CommandMatch
import com.firestream.chat.domain.command.CommandPath

internal data class CommandsState(
    val isPaletteOpen: Boolean = false,
    val currentPath: CommandPath = CommandPath.ROOT,
    // Each candidate carries its own full path, because a row is not always a direct
    // child of currentPath — a fallback subtree match (`.timer.set` shown while
    // browsing root) has to navigate to its whole path when tapped.
    val candidates: List<CommandMatch> = emptyList(),
    val activeWidget: ChatCommandWidget? = null,
    val filter: String = "",
    val exactAlarmBannerVisible: Boolean = false,
)
