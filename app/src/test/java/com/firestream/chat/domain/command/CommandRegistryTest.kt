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

    @Test
    fun `search returns direct children when the current level matches`() {
        val set = cmd("set")
        val timer = cmd("timer", children = listOf(set))
        val registry = CommandRegistry(setOf(timer))

        val results = registry.search(CommandPath.ROOT, "tim")

        assertEquals(listOf("timer"), results.map { it.command.id })
        assertEquals(listOf(CommandPath.of("timer")), results.map { it.path })
        assertTrue(results.none { it.isDeep })
    }

    @Test
    fun `search falls back to the subtree when the current level misses`() {
        val set = cmd("set")
        val timer = cmd("timer", children = listOf(set))
        val remind = cmd("remind")
        val registry = CommandRegistry(setOf(timer, remind))

        val results = registry.search(CommandPath.ROOT, "set")

        assertEquals(listOf("set"), results.map { it.command.id })
        assertEquals(CommandPath.of("timer", "set"), results.single().path)
        assertTrue(results.single().isDeep)
    }

    @Test
    fun `search does not fall back while the current level still matches`() {
        // "s" matches the root command `.send` directly, so the nested `.timer.set`
        // must stay hidden — the level the user is browsing wins outright.
        val set = cmd("set")
        val timer = cmd("timer", children = listOf(set))
        val send = cmd("send")
        val registry = CommandRegistry(setOf(timer, send))

        val results = registry.search(CommandPath.ROOT, "s")

        assertEquals(listOf("send"), results.map { it.command.id })
        assertTrue(results.none { it.isDeep })
    }

    @Test
    fun `search does not fall back on a blank filter`() {
        // A leaf has no children; a bare `.` there must not spill the whole tree in.
        val set = cmd("set")
        val timer = cmd("timer", children = listOf(set))
        val registry = CommandRegistry(setOf(timer))

        assertTrue(registry.search(CommandPath.of("timer", "set"), "").isEmpty())
    }

    @Test
    fun `search fallback ranks shallower matches first`() {
        val deep = cmd("stop", children = emptyList())
        val mid = cmd("nested", children = listOf(deep))
        val shallow = cmd("start")
        val timer = cmd("timer", children = listOf(mid, shallow))
        val registry = CommandRegistry(setOf(timer))

        val results = registry.search(CommandPath.ROOT, "st")

        assertEquals(listOf("start", "stop"), results.map { it.command.id })
        assertEquals(
            listOf(CommandPath.of("timer", "start"), CommandPath.of("timer", "nested", "stop")),
            results.map { it.path },
        )
    }

    @Test
    fun `search fallback keeps same-id matches under different parents apart`() {
        val timerSet = cmd("set")
        val alarmSet = cmd("set")
        val timer = cmd("timer", children = listOf(timerSet))
        val alarm = cmd("alarm", children = listOf(alarmSet))
        val registry = CommandRegistry(setOf(timer, alarm))

        val results = registry.search(CommandPath.ROOT, "set")

        // Both surface, and their paths differ — the palette keys rows on the path
        // precisely because the ids collide here.
        assertEquals(
            listOf(CommandPath.of("alarm", "set"), CommandPath.of("timer", "set")),
            results.map { it.path },
        )
    }

    @Test
    fun `search fallback below a non-root path stays inside that subtree`() {
        val deep = cmd("gentle")
        val sound = cmd("sound", children = listOf(deep))
        val timer = cmd("timer", children = listOf(sound))
        val otherGentle = cmd("gentle")
        val torch = cmd("torch", children = listOf(otherGentle))
        val registry = CommandRegistry(setOf(timer, torch))

        val results = registry.search(CommandPath.of("timer"), "gen")

        assertEquals(CommandPath.of("timer", "sound", "gentle"), results.single().path)
    }

    @Test
    fun `search returns nothing when the filter matches nowhere in the tree`() {
        val set = cmd("set")
        val timer = cmd("timer", children = listOf(set))
        val registry = CommandRegistry(setOf(timer))

        assertTrue(registry.search(CommandPath.ROOT, "zzz").isEmpty())
    }

    private class TestCommand(
        override val id: String,
        override val children: List<ChatCommand>,
        override val widget: ChatCommandWidget?,
    ) : ChatCommand {
        override val displayName: String = ".$id"
    }
}
