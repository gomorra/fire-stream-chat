package com.firestream.chat.domain.command

@JvmInline
value class CommandPath(val segments: List<String>) {
    val isRoot: Boolean get() = segments.isEmpty()
    val depth: Int get() = segments.size
    val leaf: String? get() = segments.lastOrNull()

    fun append(segment: String): CommandPath = CommandPath(segments + segment)
    fun parent(): CommandPath = if (isRoot) ROOT else CommandPath(segments.dropLast(1))

    fun displayString(): String = if (isRoot) "" else segments.joinToString(separator = ".", prefix = ".")

    companion object {
        val ROOT: CommandPath = CommandPath(emptyList())
        fun of(vararg segments: String): CommandPath = CommandPath(segments.toList())
    }
}
