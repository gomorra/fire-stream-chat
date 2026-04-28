package com.firestream.chat.data.remote.pocketbase

import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * derivePresence is the freshness-window guard between the heartbeat (20s
 * cadence) and the cron sweeper (30s, 60s threshold). If the heartbeat
 * stalls but the sweeper hasn't run yet, the UI must already show offline —
 * otherwise we'd render a stale "online" indicator for up to 30s on top of
 * an already-dead client.
 */
class PocketBasePresenceFreshnessTest {

    private val subject = PocketBasePresenceSource(
        client = mockk(relaxed = true),
        realtime = mockk(relaxed = true)
    )

    @Test
    fun `derivePresence returns false when is_online is false`() {
        val record = JSONObject().apply {
            put("is_online", false)
            put("last_heartbeat", System.currentTimeMillis())
        }
        assertFalse(subject.derivePresence(record))
    }

    @Test
    fun `derivePresence returns true when is_online and heartbeat is recent`() {
        val record = JSONObject().apply {
            put("is_online", true)
            put("last_heartbeat", System.currentTimeMillis() - 5_000L)
        }
        assertTrue(subject.derivePresence(record))
    }

    @Test
    fun `derivePresence returns false when heartbeat is older than freshness window`() {
        val record = JSONObject().apply {
            put("is_online", true)
            // 90s old: well past the 60s window. Even though the row says
            // is_online=true, the client is silent — show offline.
            put("last_heartbeat", System.currentTimeMillis() - 90_000L)
        }
        assertFalse(subject.derivePresence(record))
    }

    @Test
    fun `derivePresence returns false when last_heartbeat field is missing`() {
        val record = JSONObject().apply {
            put("is_online", true)
            // Defaults to 0L which is far older than the freshness window.
        }
        assertFalse(subject.derivePresence(record))
    }
}
