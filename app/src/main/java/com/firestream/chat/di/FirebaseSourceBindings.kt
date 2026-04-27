package com.firestream.chat.di

import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirebaseKeySource
import com.firestream.chat.data.remote.firebase.FirebaseStorageSource
import com.firestream.chat.data.remote.firebase.FirestoreCallSource
import com.firestream.chat.data.remote.firebase.FirestoreChatSource
import com.firestream.chat.data.remote.firebase.FirestoreContactSource
import com.firestream.chat.data.remote.firebase.FirestoreListHistorySource
import com.firestream.chat.data.remote.firebase.FirestoreListSource
import com.firestream.chat.data.remote.firebase.FirestoreMessageSource
import com.firestream.chat.data.remote.firebase.FirestoreUserSource
import com.firestream.chat.data.remote.firebase.RealtimePresenceSource
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.CallSignalingSource
import com.firestream.chat.data.remote.source.ChatSource
import com.firestream.chat.data.remote.source.ContactSource
import com.firestream.chat.data.remote.source.KeySource
import com.firestream.chat.data.remote.source.ListHistorySource
import com.firestream.chat.data.remote.source.ListSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.PresenceSource
import com.firestream.chat.data.remote.source.StorageSource
import com.firestream.chat.data.remote.source.UserSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the 11 backend-neutral `*Source` interfaces in `data/remote/source/` to
 * their Firebase concretes in `data/remote/firebase/`. Lives here in `main/` so
 * the firebase variant has bindings while the codebase still lives in one
 * source set; step 3.B moves this file (and the impls it references) into
 * `app/src/firebase/` and the pocketbase variant supplies its own bindings
 * pointing at `PocketBase*Source` impls.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FirebaseSourceBindings {

    @Binds @Singleton
    abstract fun bindAuthSource(impl: FirebaseAuthSource): AuthSource

    @Binds @Singleton
    abstract fun bindUserSource(impl: FirestoreUserSource): UserSource

    @Binds @Singleton
    abstract fun bindChatSource(impl: FirestoreChatSource): ChatSource

    @Binds @Singleton
    abstract fun bindMessageSource(impl: FirestoreMessageSource): MessageSource

    @Binds @Singleton
    abstract fun bindPresenceSource(impl: RealtimePresenceSource): PresenceSource

    @Binds @Singleton
    abstract fun bindStorageSource(impl: FirebaseStorageSource): StorageSource

    @Binds @Singleton
    abstract fun bindCallSignalingSource(impl: FirestoreCallSource): CallSignalingSource

    @Binds @Singleton
    abstract fun bindKeySource(impl: FirebaseKeySource): KeySource

    @Binds @Singleton
    abstract fun bindListSource(impl: FirestoreListSource): ListSource

    @Binds @Singleton
    abstract fun bindListHistorySource(impl: FirestoreListHistorySource): ListHistorySource

    @Binds @Singleton
    abstract fun bindContactSource(impl: FirestoreContactSource): ContactSource
}
