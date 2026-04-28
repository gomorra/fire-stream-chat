package com.firestream.chat.data.remote.pocketbase

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PbUserMapperTest {

    @Test
    fun `fromRecord maps PB schema field names to domain model`() {
        val record = JSONObject().apply {
            put("id", "u_42")
            put("phone", "+15551234567")
            put("name", "Bob")
            put("avatar_url", "https://cdn/b.jpg")
            put("status_text", "Custom status")
        }

        val user = PbUserMapper.fromRecord(record)

        assertEquals("u_42", user.uid)
        assertEquals("+15551234567", user.phoneNumber)
        assertEquals("Bob", user.displayName)
        assertEquals("https://cdn/b.jpg", user.avatarUrl)
        assertEquals("Custom status", user.statusText)
        assertTrue(user.readReceiptsEnabled)
    }

    @Test
    fun `fromRecord falls back to default status text when blank`() {
        val record = JSONObject().apply {
            put("id", "u_7")
            put("phone", "+1")
            put("name", "Alice")
            put("avatar_url", "")
            put("status_text", "")
        }

        val user = PbUserMapper.fromRecord(record)

        assertNull("blank avatar_url should map to null", user.avatarUrl)
        assertEquals("Hey there! I'm using FireStream", user.statusText)
    }

    @Test
    fun `toPbUpdates renames domain field names to PB schema`() {
        val updates = mapOf(
            "phoneNumber" to "+1",
            "displayName" to "Carol",
            "avatarUrl" to "https://cdn/c.jpg",
            "statusText" to "On vacation",
            "fcmToken" to "tok_xyz"
        )

        val out = PbUserMapper.toPbUpdates(updates)

        assertEquals("+1", out["phone"])
        assertEquals("Carol", out["name"])
        assertEquals("https://cdn/c.jpg", out["avatar_url"])
        assertEquals("On vacation", out["status_text"])
        assertEquals("tok_xyz", out["fcm_token"])
        // Original camelCase keys must not leak through.
        assertFalse(out.containsKey("phoneNumber"))
        assertFalse(out.containsKey("displayName"))
        assertFalse(out.containsKey("avatarUrl"))
        assertFalse(out.containsKey("statusText"))
        assertFalse(out.containsKey("fcmToken"))
    }

    @Test
    fun `toPbUpdates drops fields not in v0 PB schema`() {
        // These five exist on the domain User but not on the PB users record.
        // Letting them through would 400 on PATCH.
        val updates = mapOf(
            "isOnline" to true,
            "lastSeen" to 1_700_000_000_000L,
            "publicIdentityKey" to "pk",
            "readReceiptsEnabled" to false,
            "localAvatarPath" to "/data/foo.jpg",
            "displayName" to "kept"
        )

        val out = PbUserMapper.toPbUpdates(updates)

        assertEquals(mapOf<String, Any?>("name" to "kept"), out)
    }

    @Test
    fun `toPbUpdates preserves unknown keys verbatim`() {
        // Forward-compat: a future schema may add a field neither side renames.
        val out = PbUserMapper.toPbUpdates(mapOf("custom_field" to 7))
        assertEquals(7, out["custom_field"])
    }
}
