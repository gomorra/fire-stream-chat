package com.firestream.chat.di

import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.crypto.SignalProtocolStoreImpl
import com.firestream.chat.data.local.dao.SignalDao
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirebaseKeySource
import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    @Provides
    @Singleton
    fun provideSignalProtocolStore(signalDao: SignalDao): SignalProtocolStoreImpl =
        SignalProtocolStoreImpl(signalDao)

    @Provides
    @Singleton
    fun provideFirebaseKeySource(firestore: FirebaseFirestore): FirebaseKeySource =
        FirebaseKeySource(firestore)

    @Provides
    @Singleton
    fun provideSignalManager(
        store: SignalProtocolStoreImpl,
        keySource: FirebaseKeySource,
        authSource: FirebaseAuthSource
    ): SignalManager = SignalManager(store, keySource, authSource)
}
