// region: AGENT-NOTE
// Responsibility: Source of truth for the set of chat `.commands` available in the
//   composer. Walks paths (List<String> segments) into the registered command tree.
// Owns: the immutable, sorted list of root commands plus the navigation algorithm.
// Collaborators: Hilt multibinding (Set<ChatCommand> via @IntoSet from di/CommandModule
//   and per-command modules), ChatCommandsManager (consumes via filterRoots / resolve).
// Don't put here: state (palette open/closed, current path, filter) — that lives in
//   CommandsState and ChatCommandsManager. Don't put widget Composables here either.
// endregion

package com.firestream.chat.domain.command

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommandRegistry @Inject constructor(
    commands: Set<@JvmSuppressWildcards ChatCommand>,
) {
    val roots: List<ChatCommand> = commands.sortedBy { it.id }

    fun resolve(path: CommandPath): ChatCommand? {
        if (path.isRoot) return null
        var current: ChatCommand? = roots.firstOrNull { it.id == path.segments.first() } ?: return null
        for (segment in path.segments.drop(1)) {
            current = current?.children?.firstOrNull { it.id == segment } ?: return null
        }
        return current
    }

    fun childrenOf(path: CommandPath): List<ChatCommand> =
        if (path.isRoot) roots else resolve(path)?.children.orEmpty()

    fun filterChildren(path: CommandPath, filter: String): List<ChatCommand> {
        val candidates = childrenOf(path)
        if (filter.isBlank()) return candidates
        val lower = filter.lowercase()
        return candidates.filter { it.id.lowercase().startsWith(lower) }
    }
}
