package com.firestream.chat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AppErrorTest {

    @Test
    fun `IOException maps to Network`() {
        assertSame(AppError.Network, AppError.from(IOException("boom")))
    }

    @Test
    fun `UnknownHostException maps to Network`() {
        assertSame(AppError.Network, AppError.from(UnknownHostException("dns")))
    }

    @Test
    fun `SocketTimeoutException maps to Network`() {
        assertSame(AppError.Network, AppError.from(SocketTimeoutException("slow")))
    }

    @Test
    fun `other throwables map to Unknown and preserve the cause`() {
        val cause = IllegalStateException("weird")
        val error = AppError.from(cause)
        assertTrue(error is AppError.Unknown)
        assertSame(cause, (error as AppError.Unknown).cause)
    }

    // An intentional refusal with a message for the user, not an unknown failure:
    // the banner shows the message, and the reason stays out of it.
    @Test
    fun `ChatNotReadyException maps to Validation with its message and without its reason`() {
        val error = AppError.from(ChatNotReadyException(chatId = "chat1", reason = "no local chat row"))

        assertEquals(AppError.Validation("This chat isn't ready yet"), error)
    }

    @Test
    fun `Unknown exposes cause message when present`() {
        assertEquals("boom", AppError.Unknown(RuntimeException("boom")).message)
    }

    @Test
    fun `Unknown falls back to default string when cause has no message`() {
        assertEquals("Something went wrong", AppError.Unknown(RuntimeException()).message)
    }

    @Test
    fun `Permission includes the action in the message`() {
        assertEquals("Not allowed to edit group", AppError.Permission("edit group").message)
    }

    @Test
    fun `NotFound includes the entity in the message`() {
        assertEquals("Chat not found", AppError.NotFound("Chat").message)
    }
}
