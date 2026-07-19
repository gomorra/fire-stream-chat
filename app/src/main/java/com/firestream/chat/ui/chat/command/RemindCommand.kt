package com.firestream.chat.ui.chat.command

import com.firestream.chat.domain.command.ChatCommand
import com.firestream.chat.domain.command.ChatCommandWidget
import com.firestream.chat.domain.model.Message
import com.firestream.chat.ui.chat.widget.RemindWidget
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Leaf `.remind` command — a power-user alternative to long-press → Snooze. Unlike
 * `.timer` (whose set/pause/cancel verbs force a child), `.remind` has a single verb,
 * so it is a leaf that mounts [RemindWidget] directly when `.remind` is typed or
 * tapped in the palette (see ChatCommandsManager.onComposerTextChanged's exact-leaf
 * branch).
 */
@Singleton
class RemindCommand @Inject constructor(
    private val widgetImpl: RemindWidget,
) : ChatCommand {
    override val id: String = "remind"
    override val displayName: String = ".remind"
    override val description: String = "Remind me about a message later"
    override val children: List<ChatCommand> = emptyList()
    override val widget: ChatCommandWidget = widgetImpl
}

/**
 * Resolves the message the `.remind` widget targets: the current reply-target if the
 * composer has one selected, else the newest message in the chat. [messages] is
 * chronological (newest last), so the newest is [List.lastOrNull]. Returns null when
 * the chat has no messages — the widget then shows a harmless empty state.
 */
internal fun resolveRemindTarget(replyTarget: Message?, messages: List<Message>): Message? =
    replyTarget ?: messages.lastOrNull()
