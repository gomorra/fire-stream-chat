package com.firestream.chat.data.repository

import android.net.ConnectivityManager
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.ChatEntity
import com.firestream.chat.data.outbox.BlockCheck
import com.firestream.chat.data.outbox.MessageWriter
import com.firestream.chat.data.outbox.OutboxFiles
import com.firestream.chat.data.outbox.OutboxScheduler
import com.firestream.chat.data.outbox.OutboxSender
import com.firestream.chat.data.outbox.SendClock
import com.firestream.chat.data.remote.fcm.ActiveChatTracker
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.data.util.DocumentFiles
import com.firestream.chat.data.util.DocumentInfo
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.data.util.VideoTranscoder
import com.firestream.chat.data.worker.MediaBackfillScheduler
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.test.TestData
import io.mockk.coEvery
import io.mockk.mockk

/**
 * Builds a [MessageRepositoryImpl] for a test. Pass, by name, the collaborators
 * the test stubs or verifies; every other one is a relaxed mock. A new
 * constructor parameter is added here once instead of in every test file.
 *
 * The default [OutboxFiles] stages nothing (`stage` answers `null`), so a send's
 * row keeps the URI it was inserted with unless a test says otherwise. The
 * [BlockCheck] is real over the [UserSource] mock, so a test stubs `isUserBlocked`.
 *
 * A send is addressed from the chat's row, so the default [ChatDao] answers
 * `getChatById` from [chats] and with `null` for any other id. The default is
 * one 1:1 chat, `"chat1"`, between `"uid1"` and `"recipient1"`. A test about
 * addressing, or one that sends into another chat, passes [chats]. A test that
 * passes its own [chatDao] stubs `getChatById` itself: a relaxed mock answers
 * with a mock row, which no send accepts.
 */
internal fun messageRepository(
    messageDao: MessageDao = mockk(relaxed = true),
    chats: List<ChatEntity> = listOf(testChat("chat1")),
    chatDao: ChatDao = mockk(relaxed = true) {
        coEvery { getChatById(any()) } answers { chats.firstOrNull { it.id == firstArg<String>() } }
    },
    messageSource: MessageSource = mockk(relaxed = true),
    authSource: AuthSource = mockk(relaxed = true),
    signalManager: SignalManager = mockk(relaxed = true),
    outboxSender: OutboxSender = mockk(relaxed = true),
    outboxScheduler: OutboxScheduler = mockk(relaxed = true),
    outboxFiles: OutboxFiles = mockk(relaxed = true) { coEvery { stage(any(), any(), any()) } returns null },
    messageWriter: MessageWriter = mockk(relaxed = true),
    chatRepository: dagger.Lazy<ChatRepository> = mockk(relaxed = true),
    listRepository: dagger.Lazy<ListRepository> = mockk(relaxed = true),
    mediaFileManager: MediaFileManager = mockk(relaxed = true),
    videoTranscoder: VideoTranscoder = mockk(relaxed = true),
    preferencesDataStore: PreferencesDataStore = mockk(relaxed = true),
    connectivityManager: ConnectivityManager = mockk(relaxed = true),
    userSource: UserSource = mockk(relaxed = true),
    blockCheck: BlockCheck = BlockCheck(userSource),
    sendClock: SendClock = SendClock(),
    activeChatTracker: ActiveChatTracker = ActiveChatTracker(),
    mediaBackfillScheduler: MediaBackfillScheduler = mockk(relaxed = true),
    documentFiles: DocumentFiles = mockk(relaxed = true) { coEvery { describe(any()) } returns DocumentInfo(null, null) },
) = MessageRepositoryImpl(
    messageDao = messageDao,
    chatDao = chatDao,
    messageSource = messageSource,
    authSource = authSource,
    signalManager = signalManager,
    outboxSender = outboxSender,
    outboxScheduler = outboxScheduler,
    outboxFiles = outboxFiles,
    blockCheck = blockCheck,
    messageWriter = messageWriter,
    chatRepository = chatRepository,
    listRepository = listRepository,
    mediaFileManager = mediaFileManager,
    videoTranscoder = videoTranscoder,
    preferencesDataStore = preferencesDataStore,
    connectivityManager = connectivityManager,
    userSource = userSource,
    sendClock = sendClock,
    activeChatTracker = activeChatTracker,
    mediaBackfillScheduler = mediaBackfillScheduler,
    documentFiles = documentFiles,
)

/**
 * A chat row as the local store holds it. The defaults are the 1:1 chat between
 * `"uid1"` (the sender in these tests) and `"recipient1"`. [type] is the stored
 * string, so a test can seed one the app does not know.
 */
internal fun testChat(
    id: String,
    type: String = ChatType.INDIVIDUAL.name,
    participants: List<String> = listOf("uid1", "recipient1"),
) = ChatEntity.fromDomain(TestData.chat(id = id, participants = participants)).copy(type = type)
