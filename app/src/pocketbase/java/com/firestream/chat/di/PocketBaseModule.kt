// region: AGENT-NOTE
// PocketBase-flavor Hilt bindings. Mirrors FirebaseSourceBindings (in
// app/src/firebase/) but points each `*Source` interface at its
// `PocketBase*Source` impl. PocketBaseClient + PocketBaseRealtime are picked
// up via @Inject constructors — no @Provides needed. KeySource is bound by
// PocketBaseCryptoModule (separate file to mirror FirebaseCryptoModule).
//
// Don't put here:
//   - KeySource binding — that's in PocketBaseCryptoModule.kt next door.
//   - Concrete client construction — PocketBaseClient/PocketBaseRealtime use
//     constructor injection.
// endregion
package com.firestream.chat.di

import com.firestream.chat.data.remote.pocketbase.PocketBaseAuthSource
import com.firestream.chat.data.remote.pocketbase.PocketBaseCallSignalingSource
import com.firestream.chat.data.remote.pocketbase.PocketBaseChatSource
import com.firestream.chat.data.remote.pocketbase.PocketBaseContactSource
import com.firestream.chat.data.remote.pocketbase.PocketBaseListHistorySource
import com.firestream.chat.data.remote.pocketbase.PocketBaseListSource
import com.firestream.chat.data.remote.pocketbase.PocketBaseMessageSource
import com.firestream.chat.data.remote.pocketbase.PocketBasePresenceSource
import com.firestream.chat.data.remote.pocketbase.PocketBaseStorageSource
import com.firestream.chat.data.remote.pocketbase.PocketBaseUserSource
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.CallSignalingSource
import com.firestream.chat.data.remote.source.ChatSource
import com.firestream.chat.data.remote.source.ContactSource
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

@Module
@InstallIn(SingletonComponent::class)
abstract class PocketBaseModule {

    @Binds @Singleton
    abstract fun bindAuthSource(impl: PocketBaseAuthSource): AuthSource

    @Binds @Singleton
    abstract fun bindUserSource(impl: PocketBaseUserSource): UserSource

    @Binds @Singleton
    abstract fun bindChatSource(impl: PocketBaseChatSource): ChatSource

    @Binds @Singleton
    abstract fun bindMessageSource(impl: PocketBaseMessageSource): MessageSource

    @Binds @Singleton
    abstract fun bindPresenceSource(impl: PocketBasePresenceSource): PresenceSource

    @Binds @Singleton
    abstract fun bindStorageSource(impl: PocketBaseStorageSource): StorageSource

    @Binds @Singleton
    abstract fun bindCallSignalingSource(impl: PocketBaseCallSignalingSource): CallSignalingSource

    @Binds @Singleton
    abstract fun bindListSource(impl: PocketBaseListSource): ListSource

    @Binds @Singleton
    abstract fun bindListHistorySource(impl: PocketBaseListHistorySource): ListHistorySource

    @Binds @Singleton
    abstract fun bindContactSource(impl: PocketBaseContactSource): ContactSource
}
