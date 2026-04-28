package com.firestream.chat.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Declares the multibound `Set<FlavorBootstrap>`. Each flavor's DI module
 * adds (or doesn't add) `@Binds @IntoSet` entries; firebase contributes
 * nothing in v0, pocketbase contributes `PocketBaseLifecycleHook`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FlavorBootstrapModule {
    @Multibinds abstract fun flavorBootstraps(): Set<FlavorBootstrap>
}
