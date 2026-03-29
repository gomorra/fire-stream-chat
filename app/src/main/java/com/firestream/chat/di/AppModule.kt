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
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
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

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun provideFirebaseFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()

    @Provides
    @Singleton
    fun provideFirebaseStorage(): FirebaseStorage = FirebaseStorage.getInstance()

    @Provides
    @Singleton
    fun provideFirebaseMessaging(): FirebaseMessaging = FirebaseMessaging.getInstance()

    @Provides
    @Singleton
    fun provideFirebaseDatabase(): FirebaseDatabase = FirebaseDatabase.getInstance()
}
