package com.firestream.chat.di

import com.firestream.chat.domain.command.ChatCommand
import com.firestream.chat.ui.chat.command.TimerCommand
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

/**
 * Declares the multibound `Set<ChatCommand>`. Each individual command (timer,
 * future torch, etc.) self-registers via its own `@Binds @IntoSet` entry.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CommandModule {
    @Multibinds abstract fun chatCommands(): Set<ChatCommand>

    @Binds
    @IntoSet
    abstract fun bindTimerCommand(impl: TimerCommand): ChatCommand
}
