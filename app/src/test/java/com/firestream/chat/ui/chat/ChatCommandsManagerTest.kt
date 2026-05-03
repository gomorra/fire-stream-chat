package com.firestream.chat.ui.chat

import androidx.compose.runtime.Composable
import com.firestream.chat.domain.command.ChatCommand
import com.firestream.chat.domain.command.ChatCommandWidget
import com.firestream.chat.domain.command.CommandPath
import com.firestream.chat.domain.command.CommandPayload
import com.firestream.chat.domain.command.CommandRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCommandsManagerTest {

    private val timerWidget = StubWidget()
    private val timerSet = TestCommand(id = "set", widget = timerWidget)
    private val timerSend = TestCommand(id = "send")
    private val timer = TestCommand(id = "timer", children = listOf(timerSet, timerSend))
    private val torch = TestCommand(id = "torch")
    private val registry = CommandRegistry(setOf(timer, torch))

    private val state = MutableStateFlow(ChatUiState())
    private val manager = ChatCommandsManager(registry, state)

    @Test
    fun `openPalette shows root commands`() {
        manager.openPalette()

        val cmds = state.value.commands
        assertTrue(cmds.isPaletteOpen)
        assertEquals(CommandPath.ROOT, cmds.currentPath)
        assertEquals(listOf("timer", "torch"), cmds.candidates.map { it.id })
    }

    @Test
    fun `closePalette resets state`() {
        manager.openPalette()
        manager.closePalette()

        val cmds = state.value.commands
        assertFalse(cmds.isPaletteOpen)
        assertTrue(cmds.candidates.isEmpty())
        assertEquals(CommandPath.ROOT, cmds.currentPath)
    }

    @Test
    fun `navigateInto a parent shows its children`() {
        manager.openPalette()
        manager.navigateInto("timer")

        val cmds = state.value.commands
        assertTrue(cmds.isPaletteOpen)
        assertEquals(CommandPath.of("timer"), cmds.currentPath)
        assertEquals(listOf("set", "send"), cmds.candidates.map { it.id })
    }

    @Test
    fun `navigateInto a leaf with widget mounts widget and closes palette`() {
        manager.openPalette()
        manager.navigateInto("timer")
        manager.navigateInto("set")

        val cmds = state.value.commands
        assertFalse(cmds.isPaletteOpen)
        assertSame(timerWidget, cmds.activeWidget)
        assertEquals(CommandPath.of("timer", "set"), cmds.currentPath)
    }

    @Test
    fun `navigateBack from a child goes to parent`() {
        manager.openPalette()
        manager.navigateInto("timer")
        manager.navigateBack()

        assertEquals(CommandPath.ROOT, state.value.commands.currentPath)
        assertEquals(listOf("timer", "torch"), state.value.commands.candidates.map { it.id })
    }

    @Test
    fun `navigateBack from active widget dismisses widget`() {
        manager.openPalette()
        manager.navigateInto("timer")
        manager.navigateInto("set")
        manager.navigateBack()

        assertNull(state.value.commands.activeWidget)
        assertFalse(state.value.commands.isPaletteOpen)
    }

    @Test
    fun `updateFilter narrows candidates`() {
        manager.openPalette()
        manager.updateFilter("ti")

        val cmds = state.value.commands
        assertEquals("ti", cmds.filter)
        assertEquals(listOf("timer"), cmds.candidates.map { it.id })
    }

    @Test
    fun `onComposerTextChanged with no leading dot closes palette`() {
        manager.openPalette()
        manager.onComposerTextChanged("hello")

        assertFalse(state.value.commands.isPaletteOpen)
    }

    @Test
    fun `onComposerTextChanged with single dot opens root palette`() {
        manager.onComposerTextChanged(".")

        val cmds = state.value.commands
        assertTrue(cmds.isPaletteOpen)
        assertEquals(CommandPath.ROOT, cmds.currentPath)
        assertEquals("", cmds.filter)
    }

    @Test
    fun `onComposerTextChanged with prefix filters candidates`() {
        manager.onComposerTextChanged(".tim")

        val cmds = state.value.commands
        assertTrue(cmds.isPaletteOpen)
        assertEquals(CommandPath.ROOT, cmds.currentPath)
        assertEquals("tim", cmds.filter)
        assertEquals(listOf("timer"), cmds.candidates.map { it.id })
    }

    @Test
    fun `onComposerTextChanged with completed segment navigates in`() {
        manager.onComposerTextChanged(".timer.")

        val cmds = state.value.commands
        assertTrue(cmds.isPaletteOpen)
        assertEquals(CommandPath.of("timer"), cmds.currentPath)
        assertEquals("", cmds.filter)
        assertEquals(listOf("set", "send"), cmds.candidates.map { it.id })
    }

    @Test
    fun `onComposerTextChanged exact leaf match mounts widget`() {
        manager.onComposerTextChanged(".timer.set")

        val cmds = state.value.commands
        assertFalse(cmds.isPaletteOpen)
        assertSame(timerWidget, cmds.activeWidget)
    }

    @Test
    fun `onComposerTextChanged unknown segment closes palette`() {
        manager.openPalette()
        manager.onComposerTextChanged(".bogus.command")

        assertFalse(state.value.commands.isPaletteOpen)
    }

    @Test
    fun `dismissWidget clears active widget`() {
        manager.mountWidget(timerWidget, CommandPath.of("timer", "set"))
        manager.dismissWidget()

        assertNull(state.value.commands.activeWidget)
        assertFalse(state.value.commands.isPaletteOpen)
    }

    private class TestCommand(
        override val id: String,
        override val children: List<ChatCommand> = emptyList(),
        override val widget: ChatCommandWidget? = null,
    ) : ChatCommand {
        override val displayName: String = ".$id"
    }

    private class StubWidget : ChatCommandWidget {
        @Composable
        override fun Render(
            chatId: String,
            composerText: String,
            onSend: (CommandPayload) -> Unit,
            onCancel: () -> Unit,
        ) = Unit
    }
}
