// region: AGENT-NOTE
// Responsibility: Drive the .command palette and active widget overlay. Owns the
//   single CommandsState slice on ChatUiState — palette open/closed, current
//   navigation path, filtered candidate list, and the mounted widget (if any).
// Owns: ChatUiState.commands (entire slice).
// Collaborators: CommandRegistry (resolves paths + children); ChatViewModel
//   composition root (calls openPalette / navigateInto / mountWidget /
//   dismissWidget). The composer wires text changes to onComposerTextChanged().
// Don't put here: widget rendering (lives in each ChatCommandWidget impl) or
//   send dispatch (the widget calls ChatViewModel.onCommandSubmit() directly via
//   the onSend lambda passed into Render). Per slice-ownership pattern, this
//   manager NEVER reads or writes other slices and NEVER calls other managers.
//   See docs/PATTERNS.md#chat-manager-slice-ownership.
// endregion

package com.firestream.chat.ui.chat

import com.firestream.chat.domain.command.ChatCommand
import com.firestream.chat.domain.command.ChatCommandWidget
import com.firestream.chat.domain.command.CommandPath
import com.firestream.chat.domain.command.CommandRegistry
import com.firestream.chat.domain.command.parseCommandText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ChatCommandsManager(
    private val registry: CommandRegistry,
    private val _uiState: MutableStateFlow<ChatUiState>,
) {

    fun openPalette() {
        _uiState.update {
            it.copy(
                commands = it.commands.copy(
                    isPaletteOpen = true,
                    currentPath = CommandPath.ROOT,
                    candidates = registry.filterChildren(CommandPath.ROOT, ""),
                    filter = "",
                )
            )
        }
    }

    fun closePalette() {
        _uiState.update {
            it.copy(
                commands = it.commands.copy(
                    isPaletteOpen = false,
                    currentPath = CommandPath.ROOT,
                    candidates = emptyList(),
                    filter = "",
                )
            )
        }
    }

    /**
     * Navigate into a command. If the command has children, the palette stays
     * open and shows the children. If it's a leaf with a widget, the palette
     * closes and the widget mounts.
     */
    fun navigateInto(commandId: String) {
        val current = _uiState.value.commands
        val newPath = current.currentPath.append(commandId)
        val command: ChatCommand = registry.resolve(newPath) ?: return

        if (command.children.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    commands = it.commands.copy(
                        isPaletteOpen = true,
                        currentPath = newPath,
                        candidates = registry.filterChildren(newPath, ""),
                        filter = "",
                    )
                )
            }
        } else {
            mountWidget(command.widget, newPath)
        }
    }

    fun navigateBack() {
        val current = _uiState.value.commands
        if (current.activeWidget != null) {
            dismissWidget()
            return
        }
        val parent = current.currentPath.parent()
        _uiState.update {
            it.copy(
                commands = it.commands.copy(
                    currentPath = parent,
                    candidates = registry.filterChildren(parent, ""),
                    filter = "",
                    isPaletteOpen = true,
                )
            )
        }
    }

    fun updateFilter(text: String) {
        val current = _uiState.value.commands
        if (!current.isPaletteOpen) return
        _uiState.update {
            it.copy(
                commands = it.commands.copy(
                    filter = text,
                    candidates = registry.filterChildren(current.currentPath, text),
                )
            )
        }
    }

    /**
     * Sync state to a parsed composer string. The composer calls this whenever
     * the input text changes. Drives palette open/close, path navigation, and
     * filter as the user types/backspaces through `.command.subcommand` text.
     *
     * Behavior contract:
     *  - text doesn't start with '.'  → close palette + dismiss widget.
     *  - "."                          → open palette at root, empty filter.
     *  - ".tim"                       → palette at root, filter "tim".
     *  - ".timer"                     → palette at root, filter "timer".
     *  - ".timer."                    → navigate into "timer", filter "".
     *  - ".timer.s"                   → navigate into "timer", filter "s".
     *  - ".timer.set" (leaf)          → mount widget, palette closed.
     */
    fun onComposerTextChanged(text: String) {
        val parsed = parseCommandText(text)
        if (parsed == null) {
            val current = _uiState.value.commands
            if (current.isPaletteOpen || current.activeWidget != null) {
                _uiState.update {
                    it.copy(
                        commands = it.commands.copy(
                            isPaletteOpen = false,
                            currentPath = CommandPath.ROOT,
                            candidates = emptyList(),
                            filter = "",
                            activeWidget = null,
                        )
                    )
                }
            }
            return
        }

        var path: CommandPath = CommandPath.ROOT
        for (segment in parsed.completedSegments) {
            if (registry.resolve(path.append(segment)) == null) {
                closePalette()
                return
            }
            path = path.append(segment)
        }

        if (parsed.pendingFilter.isNotEmpty()) {
            val exactLeaf = registry.filterChildren(path, parsed.pendingFilter)
                .firstOrNull { it.id == parsed.pendingFilter && it.children.isEmpty() && it.widget != null }
            if (exactLeaf != null) {
                mountWidget(exactLeaf.widget, path.append(exactLeaf.id))
                return
            }
        } else {
            val activeLeaf = registry.resolve(path)
            if (activeLeaf != null && activeLeaf.children.isEmpty() && activeLeaf.widget != null) {
                mountWidget(activeLeaf.widget, path)
                return
            }
        }

        _uiState.update {
            it.copy(
                commands = it.commands.copy(
                    isPaletteOpen = true,
                    currentPath = path,
                    filter = parsed.pendingFilter,
                    candidates = registry.filterChildren(path, parsed.pendingFilter),
                    activeWidget = null,
                )
            )
        }
    }

    fun mountWidget(widget: ChatCommandWidget?, path: CommandPath) {
        if (widget == null) return
        _uiState.update {
            it.copy(
                commands = it.commands.copy(
                    isPaletteOpen = false,
                    currentPath = path,
                    candidates = emptyList(),
                    filter = "",
                    activeWidget = widget,
                )
            )
        }
    }

    fun dismissWidget() {
        _uiState.update {
            it.copy(
                commands = it.commands.copy(
                    isPaletteOpen = false,
                    currentPath = CommandPath.ROOT,
                    candidates = emptyList(),
                    filter = "",
                    activeWidget = null,
                )
            )
        }
    }

    fun setExactAlarmBannerVisible(visible: Boolean) {
        _uiState.update {
            it.copy(commands = it.commands.copy(exactAlarmBannerVisible = visible))
        }
    }
}
