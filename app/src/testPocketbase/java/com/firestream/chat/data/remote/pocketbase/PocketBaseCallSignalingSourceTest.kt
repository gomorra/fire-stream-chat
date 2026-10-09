package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.call.IceServerProvider
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.outbox.SendClock
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.repository.CallRepositoryImpl
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PocketBase backend has no calls. A call that is placed anyway must fail, not crash. */
class PocketBaseCallSignalingSourceTest {

    @Test
    fun `placing a call fails instead of crashing the app`() = runTest {
        val repository = CallRepositoryImpl(
            callSource = PocketBaseCallSignalingSource(),
            authSource = mockk<AuthSource>(relaxed = true) { every { currentUserId } returns "me" },
            messageSource = mockk<MessageSource>(),
            chatDao = mockk<ChatDao>(relaxed = true),
            sendClock = SendClock(),
            // No relay on this backend: the provider answers with STUN alone.
            iceServerProvider = IceServerProvider(PocketBaseIceServerSource(), backgroundScope),
        )

        val result = repository.createCall("u2", video = false)

        assertTrue(result.exceptionOrNull() is UnsupportedOperationException)
    }
}
