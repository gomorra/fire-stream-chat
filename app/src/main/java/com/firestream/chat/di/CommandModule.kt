package com.firestream.chat.di

import com.firestream.chat.domain.command.ChatCommand
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Declares the multibound `Set<ChatCommand>`. Each individual command (timer,
 * future torch, etc.) self-registers via its own Hilt module with a
 * `@Binds @IntoSet abstract fun bindXxx(impl: XxxCommand): ChatCommand` entry.
 * If no commands are contributed yet (e.g. during Step 1), the registry simply
 * sees an empty set.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CommandModule {
    @Multibinds abstract fun chatCommands(): Set<ChatCommand>
}
