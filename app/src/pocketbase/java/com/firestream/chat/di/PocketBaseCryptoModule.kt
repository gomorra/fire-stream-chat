package com.firestream.chat.di

import com.firestream.chat.data.remote.pocketbase.PocketBaseKeySource
import com.firestream.chat.data.remote.source.KeySource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Mirrors FirebaseCryptoModule but binds the stub PocketBaseKeySource. Lives
 * in a separate module from PocketBaseModule so the prekey-distribution
 * binding is colocated with the SignalManager wiring conceptually — and so
 * the follow-up Signal-on-PB plan can replace just this one file.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PocketBaseCryptoModule {

    @Binds @Singleton
    abstract fun bindKeySource(impl: PocketBaseKeySource): KeySource
}
