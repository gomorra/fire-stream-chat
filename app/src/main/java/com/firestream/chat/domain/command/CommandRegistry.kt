// region: AGENT-NOTE
// Responsibility: Source of truth for the set of chat `.commands` available in the
//   composer. Walks paths (List<String> segments) into the registered command tree,
//   and searches it (current level first, subtree as fallback).
// Owns: the immutable, sorted list of root commands, the navigation algorithm, and
//   the palette's candidate-matching rules.
// Collaborators: Hilt multibinding (Set<ChatCommand> via @IntoSet from di/CommandModule
//   and per-command modules), ChatCommandsManager (consumes via search / resolve).
// Don't put here: state (palette open/closed, current path, filter) — that lives in
//   CommandsState and ChatCommandsManager. Don't put widget Composables here either.
// endregion

package com.firestream.chat.domain.command

import javax.inject.Inject
import javax.inject.Singleton

/**
 * One palette row: a [command] plus the full [path] that reaches it from the root.
 *
 * [isDeep] marks a row that came from the fallback subtree search rather than from
 * the level the user is browsing. The palette renders those by their full path
 * (`.timer.set`) instead of their bare display name (`.set`), which on its own
 * wouldn't say where the command lives — and tapping one has to navigate to
 * [path] wholesale, not append a single segment to the current path.
 */
data class CommandMatch(
    val command: ChatCommand,
    val path: CommandPath,
    val isDeep: Boolean,
)

/**
 * Ceiling on how far [CommandRegistry.search]'s fallback descends. The command tree
 * is static and two levels deep today, so this never binds in practice; it exists so
 * that a tree that somehow contained a cycle would bound the search instead of
 * hanging the composer on every keystroke.
 */
private const val MAX_FALLBACK_DEPTH = 6

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

    /**
     * The palette's candidate rows at [path] for [filter].
     *
     * The current level wins outright: if any direct child matches, those matches are
     * the entire result. Only when the level comes up empty — the user typed `.set` at
     * root, where the roots are `.remind` and `.timer` — does this fall back to
     * searching the subtree below [path], so a command the user knows by its verb alone
     * stays reachable without knowing its parent. Fallback rows are flagged
     * [CommandMatch.isDeep] and ordered breadth-first, so shallower commands rank above
     * deeper ones.
     *
     * A blank filter never falls back: an empty level then means "this is a leaf", not
     * "your search found nothing", and descending would fill the palette with the whole
     * tree the moment the user typed a bare `.`.
     */
    fun search(path: CommandPath, filter: String): List<CommandMatch> {
        val direct = filterChildren(path, filter).map { CommandMatch(it, path.append(it.id), isDeep = false) }
        if (direct.isNotEmpty() || filter.isBlank()) return direct
        return searchDescendants(path, filter.lowercase())
    }

    /**
     * Breadth-first walk of everything under [root], collecting id-prefix matches for
     * [lowerFilter] (already lowercased by the caller). The first frontier is [root]'s
     * own children: re-testing them costs one comparison each and can't add matches,
     * since [search] only calls this after that level came up empty.
     */
    private fun searchDescendants(root: CommandPath, lowerFilter: String): List<CommandMatch> {
        val matches = mutableListOf<CommandMatch>()
        var frontier: List<Pair<ChatCommand, CommandPath>> =
            childrenOf(root).map { it to root.append(it.id) }
        var depth = 0

        while (frontier.isNotEmpty() && depth < MAX_FALLBACK_DEPTH) {
            val next = mutableListOf<Pair<ChatCommand, CommandPath>>()
            for ((command, commandPath) in frontier) {
                if (command.id.lowercase().startsWith(lowerFilter)) {
                    matches += CommandMatch(command, commandPath, isDeep = true)
                }
                command.children.forEach { child -> next += child to commandPath.append(child.id) }
            }
            frontier = next
            depth++
        }
        return matches
    }
}
