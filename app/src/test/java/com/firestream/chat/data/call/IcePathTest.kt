package com.firestream.chat.data.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcePathTest {

    private fun candidate(type: String) =
        "candidate:842163049 1 udp 1677729535 203.0.113.7 46154 typ $type raddr 0.0.0.0 rport 0 generation 0"

    @Test
    fun `host to server-reflexive is direct`() {
        val path = IcePath.of(candidate("host"), candidate("srflx"))

        assertFalse(path.isRelayed)
        assertEquals("direct (local host, remote srflx)", path.describe())
    }

    @Test
    fun `peer-reflexive is direct`() {
        assertFalse(IcePath.of(candidate("prflx"), candidate("host")).isRelayed)
    }

    @Test
    fun `a relay candidate on this side is relayed`() {
        val path = IcePath.of(candidate("relay"), candidate("srflx"))

        assertTrue(path.isRelayed)
        assertEquals("relayed (local relay, remote srflx)", path.describe())
    }

    @Test
    fun `a relay candidate on the remote side is relayed`() {
        assertTrue(IcePath.of(candidate("host"), candidate("relay")).isRelayed)
    }

    @Test
    fun `a candidate line without a type reads as unknown, not as direct`() {
        val path = IcePath.of("candidate:1 1 udp 1 10.0.0.1 1000", null)

        assertNull(path.localType)
        assertNull(path.remoteType)
        assertEquals("unknown (local unknown, remote unknown)", path.describe())
        assertEquals("unknown (local host, remote unknown)", IcePath.of(candidate("host"), null).describe())
    }
}
