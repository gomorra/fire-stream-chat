package com.firestream.chat.data.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.SignalDatabase
import com.firestream.chat.data.local.dao.SignalDao
import com.firestream.chat.data.local.entity.SignalSessionEntity
import com.firestream.chat.data.local.entity.SignalTrustedIdentityEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.KeySource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val ALICE = "alice"
private const val BOB = "bob"
private const val CHARLIE = "charlie"

/** How long a second call gets to reach a session another call holds, were there no lock. */
private const val INTERLEAVE_WINDOW_MS = 300L
private const val LOCK_WAIT_MS = 2_000L

/**
 * [SignalManager]'s session locks, rotating one-time pre-key pool, and
 * stale-bundle guard with real libsignal on both ends: parties on separate
 * in-memory `signal.db` instances exchanging bundles through [FakeKeyServer].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class SignalManagerTest {

    private val keyServer = FakeKeyServer()
    private val databases = mutableListOf<SignalDatabase>()

    @After
    fun tearDown() {
        databases.forEach { it.close() }
    }

    @Test
    fun `two encrypts for one peer run one at a time`() = runTest {
        val (alice, bob) = registeredPair()
        val gate = Gate()
        alice.dao.onSessionRead = gate::hook

        val first = async(Dispatchers.IO) { alice.manager.encrypt(BOB, "one") }
        val second = async(Dispatchers.IO) { alice.manager.encrypt(BOB, "two") }
        gate.holdThenRelease()
        val sent = listOf(first.await(), second.await())

        assertFalse("a second encrypt reached the session while the first held it", gate.intruded.get())
        assertEquals(setOf("one", "two"), sent.map { bob.manager.decrypt(ALICE, it) }.toSet())
    }

    @Test
    fun `an encrypt and a decrypt for one peer run one at a time`() = runTest {
        val (alice, bob) = registeredPair()
        assertEquals("first", bob.manager.decrypt(ALICE, alice.manager.encrypt(BOB, "first")))
        val incoming = alice.manager.encrypt(BOB, "second")
        val gate = Gate()
        bob.dao.onSessionRead = gate::hook

        val reply = async(Dispatchers.IO) { bob.manager.encrypt(ALICE, "reply") }
        val decrypted = async(Dispatchers.IO) { bob.manager.decrypt(ALICE, incoming) }
        gate.holdThenRelease()

        assertEquals("second", decrypted.await())
        assertFalse("an encrypt and a decrypt for one peer interleaved", gate.intruded.get())
        assertEquals("reply", alice.manager.decrypt(BOB, reply.await()))
    }

    @Test
    fun `a pre-key decrypt releases the peer's session before publishing replacement pre-keys`() = runTest {
        // Watermark = 2 with batch = 2: consuming one key drops count to 1 (< 2) and triggers upload.
        val alice = party(ALICE, batchSize = 2, lowWatermark = 2)
        val bob = party(BOB, batchSize = 2, lowWatermark = 2)
        alice.manager.ensureInitialized()
        bob.manager.ensureInitialized()

        val firstContact = alice.manager.encrypt(BOB, "hello")
        assertEquals(CiphertextMessage.PREKEY_TYPE, firstContact.signalType)

        val sendDuringUpload = AtomicReference<Result<EncryptedMessage>>()
        keyServer.onUploadPreKeys = { uid ->
            if (uid == BOB) {
                sendDuringUpload.set(runCatching { withTimeout(LOCK_WAIT_MS) { bob.manager.encrypt(ALICE, "reply") } })
            }
        }

        assertEquals("hello", bob.manager.decrypt(ALICE, firstContact))

        val result = sendDuringUpload.get()
        assertNotNull("replacement pre-keys were never uploaded", result)
        assertTrue("the send waited on the decrypt's session lock: ${result!!.exceptionOrNull()}", result.isSuccess)
        assertEquals("reply", alice.manager.decrypt(BOB, result.getOrThrow()))
    }

    @Test
    fun `two new contacts claim distinct one-time pre-keys and both decrypt cleanly`() = runTest {
        val alice = party(ALICE, batchSize = 5, lowWatermark = 2)
        val bob = party(BOB, batchSize = 5, lowWatermark = 2)
        val charlie = party(CHARLIE, batchSize = 5, lowWatermark = 2)
        alice.manager.ensureInitialized()
        bob.manager.ensureInitialized()
        charlie.manager.ensureInitialized()

        // Both Alice and Charlie start sessions with Bob concurrently before Bob decrypts either message.
        val aliceJob = async(Dispatchers.IO) { alice.manager.encrypt(BOB, "hi from alice") }
        val charlieJob = async(Dispatchers.IO) { charlie.manager.encrypt(BOB, "hi from charlie") }
        val fromAlice = aliceJob.await()
        val fromCharlie = charlieJob.await()

        assertEquals(CiphertextMessage.PREKEY_TYPE, fromAlice.signalType)
        assertEquals(CiphertextMessage.PREKEY_TYPE, fromCharlie.signalType)
        assertEquals(3, keyServer.remainingPreKeys(BOB))

        assertEquals("hi from alice", bob.manager.decrypt(ALICE, fromAlice))
        assertEquals("hi from charlie", bob.manager.decrypt(CHARLIE, fromCharlie))
    }

    @Test
    fun `steady-state sends and identity checks do not consume one-time pre-keys`() = runTest {
        val (alice, bob) = registeredPair(batchSize = 5, lowWatermark = 2)
        val initialPool = keyServer.remainingPreKeys(BOB)
        assertEquals(5, initialPool)

        // First message establishes session and claims exactly 1 pre-key.
        val m1 = alice.manager.encrypt(BOB, "first")
        assertEquals(4, keyServer.remainingPreKeys(BOB))
        assertEquals("first", bob.manager.decrypt(ALICE, m1))

        // Subsequent messages and identity checks consume zero pre-keys.
        repeat(5) { idx ->
            val msg = alice.manager.encrypt(BOB, "msg-$idx")
            assertTrue(alice.manager.isCurrentIdentity(BOB, msg.peerIdentity!!))
            assertEquals("msg-$idx", bob.manager.decrypt(ALICE, msg))
        }
        assertEquals(4, keyServer.remainingPreKeys(BOB))
    }

    @Test
    fun `session succeeds via signed and Kyber fallback when one-time pre-key pool is empty`() = runTest {
        val (alice, bob) = registeredPair(batchSize = 2, lowWatermark = 1)
        keyServer.drainPreKeys(BOB)
        assertEquals(0, keyServer.remainingPreKeys(BOB))

        val msg = alice.manager.encrypt(BOB, "no-otpk-needed")
        assertEquals(CiphertextMessage.PREKEY_TYPE, msg.signalType)
        assertEquals("no-otpk-needed", bob.manager.decrypt(ALICE, msg))
    }

    @Test
    fun `failed replenish upload keeps local pre-keys pending and retries on next ensureInitialized`() = runTest {
        val alice = party(ALICE, batchSize = 3, lowWatermark = 3)
        val bob = party(BOB, batchSize = 3, lowWatermark = 3)
        alice.manager.ensureInitialized()
        bob.manager.ensureInitialized()

        // Fail the upload triggered right after Bob decrypts Alice's PREKEY message.
        val failNextUpload = AtomicBoolean(true)
        keyServer.onUploadPreKeys = { uid ->
            if (uid == BOB && failNextUpload.getAndSet(false)) {
                throw IOException("network down")
            }
        }

        val first = alice.manager.encrypt(BOB, "hello")
        // Decrypt still succeeds despite replenish upload failure.
        assertEquals("hello", bob.manager.decrypt(ALICE, first))
        // Locally generated replacement key is still persisted with uploaded=false.
        assertEquals(1, bob.dao.getUnuploadedPreKeys().size)
        assertEquals(2, keyServer.remainingPreKeys(BOB))

        // Next ensureInitialized flushes the pending pre-key to the server.
        bob.manager.ensureInitialized()
        assertEquals(0, bob.dao.getUnuploadedPreKeys().size)
        assertEquals(3, keyServer.remainingPreKeys(BOB))
    }

    @Test
    fun `a ciphertext's peer identity stops being current when the peer re-registers`() = runTest {
        val (alice, _) = registeredPair()
        val stored = alice.manager.encrypt(BOB, "before")
        assertNotNull(stored.peerIdentity)
        assertTrue(alice.manager.isCurrentIdentity(BOB, stored.peerIdentity!!))

        // Bob reinstalls: a fresh store and a new identity under the same uid.
        val bobAgain = party(BOB)
        bobAgain.manager.ensureInitialized()

        assertFalse(alice.manager.isCurrentIdentity(BOB, stored.peerIdentity!!))
        val fresh = alice.manager.encrypt(BOB, "after")
        assertTrue(alice.manager.isCurrentIdentity(BOB, fresh.peerIdentity!!))
        assertEquals("after", bobAgain.manager.decrypt(ALICE, fresh))
    }

    @Test
    fun `stale unlocked identity read does not tear down a session just rebuilt on the newer identity`() = runTest {
        val (alice, bobOld) = registeredPair()
        // Establish initial session with bobOld and remember bobOld's identity.
        val oldCipher = alice.manager.encrypt(BOB, "to-old")
        assertEquals("to-old", bobOld.manager.decrypt(ALICE, oldCipher))
        val oldIdentity = keyServer.fetchIdentityKey(BOB)!!

        // Bob re-registers with a new identity; Alice establishes a session with bobNew
        // and Bob replies once so Alice's subsequent sends use WHISPER_TYPE.
        val bobNew = party(BOB)
        bobNew.manager.ensureInitialized()
        val afterReregister = alice.manager.encrypt(BOB, "to-new-1")
        assertEquals("to-new-1", bobNew.manager.decrypt(ALICE, afterReregister))
        assertEquals("ack", alice.manager.decrypt(BOB, bobNew.manager.encrypt(ALICE, "ack")))
        val preKeysBefore = keyServer.remainingPreKeys(BOB)

        // Simulate a stale unlocked read returning oldIdentity on the first fetch,
        // while the re-fetch inside the lock sees bobNew's current identity.
        val staleInjected = AtomicInteger(0)
        keyServer.onFetchIdentity = { uid ->
            if (uid == BOB && staleInjected.getAndIncrement() == 0) oldIdentity else null
        }

        val next = alice.manager.encrypt(BOB, "to-new-2")
        // Session was NOT torn down: stays WHISPER_TYPE and consumes zero pre-keys.
        assertEquals(CiphertextMessage.WHISPER_TYPE, next.signalType)
        assertEquals(preKeysBefore, keyServer.remainingPreKeys(BOB))
        assertEquals("to-new-2", bobNew.manager.decrypt(ALICE, next))
    }

    @Test
    fun `ensureInitialized replenishes when remote pool was drained even if local pre-keys remain`() = runTest {
        val bob = party(BOB, batchSize = 5, lowWatermark = 3)
        bob.manager.ensureInitialized()
        assertEquals(5, keyServer.remainingPreKeys(BOB))

        // Simulate remote pool exhaustion (abandoned claims) while all 5 keys stay in signal.db.
        keyServer.drainPreKeys(BOB)
        assertEquals(0, keyServer.remainingPreKeys(BOB))
        assertEquals(5, bob.dao.countPreKeys())

        bob.manager.ensureInitialized()
        assertEquals(5, keyServer.remainingPreKeys(BOB))
    }

    @Test
    fun `decrypt returns promptly when replenish upload hangs offline`() = runTest {
        val alice = party(ALICE, batchSize = 2, lowWatermark = 2, replenishTimeoutMs = 200L)
        val bob = party(BOB, batchSize = 2, lowWatermark = 2, replenishTimeoutMs = 200L)
        alice.manager.ensureInitialized()
        bob.manager.ensureInitialized()

        val first = alice.manager.encrypt(BOB, "offline-check")
        keyServer.onUploadPreKeys = { uid ->
            if (uid == BOB) kotlinx.coroutines.delay(10_000L)
        }

        val plaintext = kotlinx.coroutines.withContext(Dispatchers.IO) {
            withTimeout(LOCK_WAIT_MS) { bob.manager.decrypt(ALICE, first) }
        }
        assertEquals("offline-check", plaintext)
    }

    @Test
    fun `a peer without a published bundle has no current identity`() = runTest {
        val alice = party(ALICE)
        alice.manager.ensureInitialized()

        assertFalse(alice.manager.isCurrentIdentity(BOB, "anything"))
    }

    // ── harness ─────────────────────────────────────────────────────────────

    private class Party(val manager: SignalManager, val dao: ObservedSignalDao)

    private fun party(
        uid: String,
        batchSize: Int = 5,
        lowWatermark: Int = 2,
        replenishTimeoutMs: Long = SignalManager.DEFAULT_REPLENISH_TIMEOUT_MS,
    ): Party {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), SignalDatabase::class.java
        ).allowMainThreadQueries().build()
        databases += db
        val dao = ObservedSignalDao(db.signalDao())
        val auth = mockk<AuthSource> { every { currentUserId } returns uid }
        return Party(
            SignalManager(
                store = SignalProtocolStoreImpl(dao),
                keySource = keyServer,
                authSource = auth,
                preKeyBatchSize = batchSize,
                preKeyLowWatermark = lowWatermark,
                replenishTimeoutMs = replenishTimeoutMs,
            ),
            dao,
        )
    }

    private suspend fun registeredPair(
        batchSize: Int = 5,
        lowWatermark: Int = 2,
    ): Pair<Party, Party> {
        val alice = party(ALICE, batchSize, lowWatermark)
        val bob = party(BOB, batchSize, lowWatermark)
        alice.manager.ensureInitialized()
        bob.manager.ensureInitialized()
        return alice to bob
    }

    /** Stands in for `keyBundles/{uid}` + `claimPreKeyBundle`: pops one-time pre-keys atomically. */
    private class FakeKeyServer : KeySource {
        private class Entry(
            val registrationId: Int,
            val identityKey: IdentityKey,
            val signedPreKey: SignedPreKeyRecord,
            val kyberPreKey: KyberPreKeyRecord,
            val oneTimePreKeys: ArrayDeque<PreKeyRecord>,
        )

        private val entries = ConcurrentHashMap<String, Entry>()

        @Volatile
        var onUploadPreKeys: (suspend (uid: String) -> Unit)? = null

        @Volatile
        var onFetchIdentity: ((uid: String) -> IdentityKey?)? = null

        override suspend fun publishKeys(
            uid: String,
            identityKey: IdentityKey,
            registrationId: Int,
            signedPreKey: SignedPreKeyRecord,
            preKeys: List<PreKeyRecord>,
            kyberPreKey: KyberPreKeyRecord,
        ) {
            val deque = ArrayDeque<PreKeyRecord>()
            deque.addAll(preKeys)
            entries[uid] = Entry(registrationId, identityKey, signedPreKey, kyberPreKey, deque)
        }

        override suspend fun uploadPreKeys(
            uid: String,
            registrationId: Int,
            preKeys: List<PreKeyRecord>,
        ) {
            onUploadPreKeys?.invoke(uid)
            val entry = entries[uid] ?: return
            synchronized(entry) {
                if (entry.registrationId == registrationId) {
                    val existingIds = entry.oneTimePreKeys.mapTo(mutableSetOf()) { it.id }
                    preKeys.filter { it.id !in existingIds }.forEach { entry.oneTimePreKeys.addLast(it) }
                }
            }
        }

        override suspend fun countRemotePreKeys(uid: String, registrationId: Int): Int? {
            val entry = entries[uid] ?: return null
            return synchronized(entry) {
                if (entry.registrationId == registrationId) entry.oneTimePreKeys.size else 0
            }
        }

        override suspend fun fetchIdentityKey(uid: String): IdentityKey? {
            onFetchIdentity?.invoke(uid)?.let { return it }
            return entries[uid]?.identityKey
        }

        override suspend fun fetchPreKeyBundle(uid: String): PreKeyBundle? {
            val entry = entries[uid] ?: return null
            val popped = synchronized(entry) { entry.oneTimePreKeys.removeFirstOrNull() }
            return PreKeyBundle(
                entry.registrationId,
                1,
                popped?.id ?: PreKeyBundle.NULL_PRE_KEY_ID,
                popped?.keyPair?.publicKey,
                entry.signedPreKey.id,
                entry.signedPreKey.keyPair.publicKey,
                entry.signedPreKey.signature,
                entry.identityKey,
                entry.kyberPreKey.id,
                entry.kyberPreKey.keyPair.publicKey,
                entry.kyberPreKey.signature,
            )
        }

        fun remainingPreKeys(uid: String): Int {
            val entry = entries[uid] ?: return 0
            return synchronized(entry) { entry.oneTimePreKeys.size }
        }

        fun drainPreKeys(uid: String) {
            val entry = entries[uid] ?: return
            synchronized(entry) { entry.oneTimePreKeys.clear() }
        }
    }

    private class ObservedSignalDao(private val dao: SignalDao) : SignalDao by dao {
        @Volatile
        var onSessionRead: (() -> Unit)? = null

        override fun getSession(address: String): SignalSessionEntity? {
            onSessionRead?.invoke()
            return dao.getSession(address)
        }

        override fun getTrustedIdentity(address: String): SignalTrustedIdentityEntity? {
            onSessionRead?.invoke()
            return dao.getTrustedIdentity(address)
        }
    }

    private class Gate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val holder = AtomicReference<Thread?>()
        val intruded = AtomicBoolean(false)

        fun hook() {
            val current = Thread.currentThread()
            if (holder.compareAndSet(null, current)) {
                entered.countDown()
                released.await(LOCK_WAIT_MS, TimeUnit.MILLISECONDS)
            } else if (holder.get() !== current && released.count > 0) {
                intruded.set(true)
            }
        }

        fun holdThenRelease() {
            assertTrue("no session operation started", entered.await(LOCK_WAIT_MS, TimeUnit.MILLISECONDS))
            Thread.sleep(INTERLEAVE_WINDOW_MS)
            released.countDown()
        }
    }
}
