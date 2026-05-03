package com.firestream.chat.domain.command

/**
 * Result of parsing the chat composer text against the `.command.subcommand`
 * grammar. Returned when `text` starts with `.`; null otherwise.
 *
 * @property completedSegments fully-typed segments separated by dots, e.g.
 *   ".timer.s" → completedSegments=[timer], pendingFilter="s".
 * @property pendingFilter the in-progress segment after the trailing dot, used
 *   as the filter prefix in the palette.
 * @property exactMatchAtTail true when the pending portion is non-empty and
 *   matches a known leaf id exactly (handled by the manager / registry).
 */
data class ParsedCommand(
    val completedSegments: List<String>,
    val pendingFilter: String,
) {
    val pathBeforePending: CommandPath get() = CommandPath(completedSegments)
}

/**
 * Parses chat-composer text. Returns null if the text doesn't activate the
 * .command DSL (i.e. doesn't start with a leading dot at message start).
 *
 * Examples:
 *   ""             → null
 *   "hello"        → null
 *   "."            → ParsedCommand([], "")
 *   ".tim"         → ParsedCommand([], "tim")
 *   ".timer"       → ParsedCommand([], "timer")
 *   ".timer."      → ParsedCommand([timer], "")
 *   ".timer.s"     → ParsedCommand([timer], "s")
 *   ".timer.set"   → ParsedCommand([timer], "set")
 *   "Hello.timer"  → null  (trigger only at message start)
 */
fun parseCommandText(text: String): ParsedCommand? {
    if (!text.startsWith(".")) return null
    val tail = text.substring(1)
    val rawSegments = tail.split('.')
    val completed = rawSegments.dropLast(1)
    val pending = rawSegments.last()
    return ParsedCommand(completed, pending)
}
