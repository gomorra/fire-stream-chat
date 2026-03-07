package com.firestream.chat.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Crypto module for Signal Protocol dependencies.
 * Will be populated in Phase 3 when E2E encryption is implemented.
 */
@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {
    // Signal Protocol bindings will be added here in Phase 3
}
