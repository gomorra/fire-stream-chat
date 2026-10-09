package com.firestream.chat.data.remote.firebase

import com.firestream.chat.data.remote.source.RemoteSticker
import com.firestream.chat.data.remote.source.RemoteStickerPack
import com.firestream.chat.data.remote.source.StickerPackChanges
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QueryDocumentSnapshot
import com.google.firebase.firestore.QuerySnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor

/**
 * The shape of a pack manifest in Firestore, and which SDK call each operation
 * makes. A manifest may be someone else's, so reading one must survive any shape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreStickerPackSourceTest {

    private val firestore = mockk<FirebaseFirestore>()
    private val collection = mockk<CollectionReference>()
    private val document = mockk<DocumentReference>()

    private lateinit var source: FirestoreStickerPackSource

    private val pack = RemoteStickerPack(
        id = "p1", ownerId = "uid1", name = "Cats", publisher = "Ana", kind = "USER", originPackId = null,
        importKey = "wa:com.cats\u0000Cats\u0000Ana", sortOrder = 2, createdAt = 3L, updatedAt = 4L,
        stickers = listOf(
            RemoteSticker("a".repeat(64), "WEBP", 512, 256, true, listOf("😺")),
            RemoteSticker("b".repeat(64), "WEBP", 96, 96, false, emptyList()),
        ),
    )

    @Before
    fun setUp() {
        every { firestore.collection("stickerPacks") } returns collection
        every { collection.document("p1") } returns document
        source = FirestoreStickerPackSource(firestore)
    }

    private fun <T> done(result: T? = null): Task<T> = mockk {
        every { isComplete } returns true
        every { isCanceled } returns false
        every { exception } returns null
        every { this@mockk.result } answers { result as T }
    }

    /** What Firestore hands back for [written]: every number is a Long. */
    private fun asRead(written: Map<String, Any?>): Map<String, Any?> = written.mapValues { (_, value) ->
        @Suppress("UNCHECKED_CAST")
        when (value) {
            is Int -> value.toLong()
            is List<*> -> value.map { if (it is Map<*, *>) asRead(it as Map<String, Any?>) else it }
            else -> value
        }
    }

    @Test
    fun `a manifest survives the round trip through a document`() {
        val written = stickerPackToDocument(pack)

        assertEquals("uid1", written["ownerId"])
        assertEquals(pack, stickerPackFromDocument("p1", asRead(written)))
    }

    // The separator of an import key is U+0000, which a stored string does not carry.
    @Test
    fun `an import key is written without its control characters and read back with them`() {
        val stored = stickerPackToDocument(pack)["importKey"] as String

        assertEquals(false, stored.any { it.isISOControl() })
        assertEquals(pack.importKey, stickerPackFromDocument("p1", mapOf("ownerId" to "uid1", "importKey" to stored))!!.importKey)
        assertNull(stickerPackFromDocument("p1", mapOf("ownerId" to "uid1", "importKey" to "%zz"))!!.importKey)
    }

    @Test
    fun `a document of any shape reads without a throw`() {
        assertNull("no owner, no manifest", stickerPackFromDocument("p1", mapOf("name" to "Cats")))

        val odd = stickerPackFromDocument(
            "p1",
            mapOf(
                "ownerId" to "uid1",
                "name" to 7L,
                "sortOrder" to "first",
                "updatedAt" to 4.0,
                "stickers" to listOf(
                    "not a map",
                    mapOf("format" to "WEBP"),
                    mapOf("id" to "a".repeat(64), "width" to "wide", "emojis" to listOf("😺", 3L)),
                ),
            ),
        )!!

        assertEquals(listOf("", "", "0", "4"), listOf(odd.name, odd.kind, odd.sortOrder.toString(), odd.updatedAt.toString()))
        assertEquals(listOf(RemoteSticker("a".repeat(64), "", 0, 0, false, listOf("😺"))), odd.stickers)
        assertEquals(emptyList<RemoteSticker>(), stickerPackFromDocument("p1", mapOf("ownerId" to "uid1", "stickers" to "none"))!!.stickers)
    }

    @Test
    fun `a pack is written under its id and deleted by it`() = runTest {
        val written = slot<Map<String, Any?>>()
        every { document.set(capture(written)) } returns done()
        every { document.delete() } returns done()

        source.writePack(pack)
        source.deletePack("p1")

        assertEquals(stickerPackToDocument(pack), written.captured)
        verify(exactly = 1) { document.delete() }
    }

    @Test
    fun `a pack is fetched by its id, and a document that is not there is no pack`() = runTest {
        val present = mockk<DocumentSnapshot> { every { data } returns asRead(stickerPackToDocument(pack)) }
        val missing = mockk<DocumentSnapshot> { every { data } returns null }
        every { document.get() } returns done(present) andThen done(missing)

        assertEquals(pack, source.fetchPack("p1"))
        assertNull(source.fetchPack("p1"))
    }

    @Test
    fun `the listener asks for the owner's packs only, and reports changed packs apart from removed ones`() = runTest {
        val query = mockk<Query>()
        val registration = mockk<ListenerRegistration>(relaxed = true)
        val listener = slot<EventListener<QuerySnapshot>>()
        every { collection.whereEqualTo("ownerId", "uid1") } returns query
        every { query.addSnapshotListener(any<Executor>(), capture(listener)) } returns registration

        val emissions = async(UnconfinedTestDispatcher(testScheduler)) { source.observeOwnPacks("uid1").take(1).toList() }
        listener.captured.onEvent(snapshot(), null)
        listener.captured.onEvent(
            snapshot(
                change(DocumentChange.Type.ADDED, "p1", asRead(stickerPackToDocument(pack))),
                change(DocumentChange.Type.REMOVED, "p2", mapOf("ownerId" to "uid1")),
                change(DocumentChange.Type.MODIFIED, "p3", mapOf("name" to "no owner")),
            ),
            null,
        )

        assertEquals(listOf(StickerPackChanges(upserted = listOf(pack), removedIds = listOf("p2"))), emissions.await())
        verify { registration.remove() }
    }

    private fun snapshot(vararg changes: DocumentChange): QuerySnapshot = mockk {
        every { documentChanges } returns changes.toList()
    }

    private fun change(type: DocumentChange.Type, id: String, data: Map<String, Any?>): DocumentChange = mockk {
        every { this@mockk.type } returns type
        every { document } returns mockk<QueryDocumentSnapshot> {
            every { this@mockk.id } returns id
            every { this@mockk.data } returns data
        }
    }
}
