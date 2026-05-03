package com.firestream.chat.domain.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRegistryTest {

    private fun cmd(id: String, children: List<ChatCommand> = emptyList(), widget: ChatCommandWidget? = null): ChatCommand =
        TestCommand(id, children, widget)

    @Test
    fun `roots are sorted by id`() {
        val timer = cmd("timer")
        val abacus = cmd("abacus")
        val torch = cmd("torch")
        val registry = CommandRegistry(setOf(timer, abacus, torch))
        assertEquals(listOf("abacus", "timer", "torch"), registry.roots.map { it.id })
    }

    @Test
    fun `resolve returns null for empty path`() {
        val registry = CommandRegistry(setOf(cmd("timer")))
        assertNull(registry.resolve(CommandPath.ROOT))
    }

    @Test
    fun `resolve walks one level into children`() {
        val set = cmd("set")
        val timer = cmd("timer", children = listOf(set))
        val registry = CommandRegistry(setOf(timer))

        assertEquals("timer", registry.resolve(CommandPath.of("timer"))?.id)
        assertEquals("set", registry.resolve(CommandPath.of("timer", "set"))?.id)
    }

    @Test
    fun `resolve returns null for unknown segment`() {
        val timer = cmd("timer")
        val registry = CommandRegistry(setOf(timer))

        assertNull(registry.resolve(CommandPath.of("torch")))
        assertNull(registry.resolve(CommandPath.of("timer", "unknown")))
    }

    @Test
    fun `childrenOf returns roots when path is empty`() {
        val registry = CommandRegistry(setOf(cmd("timer"), cmd("torch")))
        assertEquals(listOf("timer", "torch"), registry.childrenOf(CommandPath.ROOT).map { it.id })
    }

    @Test
    fun `childrenOf returns command's children`() {
        val set = cmd("set")
        val send = cmd("send")
        val timer = cmd("timer", children = listOf(set, send))
        val registry = CommandRegistry(setOf(timer))

        assertEquals(listOf("set", "send"), registry.childrenOf(CommandPath.of("timer")).map { it.id })
    }

    @Test
    fun `filterChildren narrows by prefix case-insensitively`() {
        val set = cmd("set")
        val send = cmd("send")
        val torch = cmd("torch")
        val timer = cmd("timer", children = listOf(set, send))
        val registry = CommandRegistry(setOf(timer, torch))

        assertEquals(listOf("torch", "timer").sorted(), registry.filterChildren(CommandPath.ROOT, "T").map { it.id }.sorted())
        assertEquals(listOf("set", "send"), registry.filterChildren(CommandPath.of("timer"), "s").map { it.id })
        assertEquals(listOf("set"), registry.filterChildren(CommandPath.of("timer"), "set").map { it.id })
        assertTrue(registry.filterChildren(CommandPath.of("timer"), "z").isEmpty())
    }

    private class TestCommand(
        override val id: String,
        override val children: List<ChatCommand>,
        override val widget: ChatCommandWidget?,
    ) : ChatCommand {
        override val displayName: String = ".$id"
    }
}
