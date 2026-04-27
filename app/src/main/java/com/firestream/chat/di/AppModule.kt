package com.firestream.chat.di

import android.content.Context
import android.net.ConnectivityManager
import com.firestream.chat.data.repository.AuthRepositoryImpl
import com.firestream.chat.data.repository.CallRepositoryImpl
import com.firestream.chat.data.repository.ChatRepositoryImpl
import com.firestream.chat.data.repository.ContactRepositoryImpl
import com.firestream.chat.data.repository.ListRepositoryImpl
import com.firestream.chat.data.repository.MessageRepositoryImpl
import com.firestream.chat.data.repository.PollRepositoryImpl
import com.firestream.chat.data.repository.UserRepositoryImpl
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.CallRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ContactRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.PollRepository
import com.firestream.chat.domain.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: MessageRepositoryImpl): MessageRepository

    @Binds
    @Singleton
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository

    @Binds
    @Singleton
    abstract fun bindContactRepository(impl: ContactRepositoryImpl): ContactRepository

    @Binds
    @Singleton
    abstract fun bindCallRepository(impl: CallRepositoryImpl): CallRepository

    @Binds
    @Singleton
    abstract fun bindPollRepository(impl: PollRepositoryImpl): PollRepository

    @Binds
    @Singleton
    abstract fun bindListRepository(impl: ListRepositoryImpl): ListRepository
}

@Module
@InstallIn(SingletonComponent::class)
object SystemModule {

    @Provides
    @Singleton
    fun provideConnectivityManager(@ApplicationContext context: Context): ConnectivityManager {
        return context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
}

/**
 * Shared-Firebase providers — visible to BOTH Gradle flavors. Both keep
 * Firebase Auth (for Phone OTP login) and Firebase Messaging (FCM device-side
 * push). The Firestore / Storage / RTDB providers live in
 * `app/src/firebase/.../di/FirebaseModule.kt` and are only on the firebase
 * variant's classpath.
 */
@Module
@InstallIn(SingletonComponent::class)
object FirebaseSharedModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun provideFirebaseMessaging(): FirebaseMessaging = FirebaseMessaging.getInstance()
}
