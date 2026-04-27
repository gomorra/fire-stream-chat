package com.firestream.chat.data.remote.pocketbase

import com.google.firebase.auth.FirebaseAuth
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The slice of [PocketBaseAuthSource] worth testing in isolation: the
 * [PocketBaseAuthSource.getUserDocument] field-remapping (PB schema → Firebase
 * shape) and its empty-name-equals-no-profile semantics. The rest of the auth
 * source talks to FirebaseAuth + the bridge endpoint and is covered by manual
 * smoke testing in step 8.
 */
class PocketBaseAuthSourceTest {

    private fun newSubject(client: PocketBaseClient): PocketBaseAuthSource {
        // FirebaseAuth is only touched by sign-in/out paths; getUserDocument
        // doesn't reach it, so a relaxed mock is fine here.
        val firebaseAuth = mockk<FirebaseAuth>(relaxed = true)
        // pbUserIdFlow is consumed by the StateFlow seed/collect; an empty
        // flow keeps currentUserId at null without DataStore IO.
        every { client.pbUserIdFlow } returns flowOf(null)
        val appScope = CoroutineScope(SupervisorJob())
        return PocketBaseAuthSource(client, firebaseAuth, appScope)
    }

    @Test
    fun `getUserDocument remaps PB record fields to Firebase-shaped map`() = runTest {
        val client = mockk<PocketBaseClient>()
        val record = JSONObject().apply {
            put("id", "u_123")
            put("phone", "+15551234567")
            put("name", "Alice")
            put("avatar_url", "https://cdn/a.jpg")
            put("status_text", "Hey")
        }
        coEvery { client.get("/api/collections/users/records/u_123") } returns record

        val result = newSubject(client).getUserDocument("u_123")

        requireNotNull(result)
        assertEquals("u_123", result["uid"])
        assertEquals("+15551234567", result["phoneNumber"])
        assertEquals("Alice", result["displayName"])
        assertEquals("https://cdn/a.jpg", result["avatarUrl"])
        assertEquals("Hey", result["statusText"])
        assertEquals(true, result["isOnline"])
    }

    @Test
    fun `getUserDocument returns null when name is empty (profile not set up)`() = runTest {
        val client = mockk<PocketBaseClient>()
        val record = JSONObject().apply {
            put("id", "u_new")
            put("phone", "+15559876543")
            put("name", "")           // bridge created this record but profile setup hasn't run
            put("avatar_url", "")
            put("status_text", "")
        }
        coEvery { client.get("/api/collections/users/records/u_new") } returns record

        val result = newSubject(client).getUserDocument("u_new")

        assertNull(result)
    }

    @Test
    fun `getUserDocument returns null when the GET fails (record missing)`() = runTest {
        val client = mockk<PocketBaseClient>()
        coEvery { client.get(any()) } throws PocketBaseHttpException(404, "{\"code\":404}")

        val result = newSubject(client).getUserDocument("u_404")

        assertNull(result)
    }
}
