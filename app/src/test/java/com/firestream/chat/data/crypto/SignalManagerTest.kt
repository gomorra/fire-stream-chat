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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val ALICE = "alice"
private const val BOB = "bob"

/** How long a second call gets to reach a session another call holds, were there no lock. */
private const val INTERLEAVE_WINDOW_MS = 300L
private const val LOCK_WAIT_MS = 2_000L

/**
 * [SignalManager]'s per-peer session lock, with real libsignal on both ends:
 * two parties, each on its own in-memory `signal.db`, exchanging bundles
 * through [FakeKeyServer]. Robolectric supplies `android.util.Base64`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
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
        // Both decrypt: neither encrypt overwrote the session the other advanced.
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
    fun `a pre-key decrypt releases the peer's session before publishing the replacement pre-key`() = runTest {
        val (alice, bob) = registeredPair()
        val firstContact = alice.manager.encrypt(BOB, "hello")
        assertEquals(CiphertextMessage.PREKEY_TYPE, firstContact.signalType)

        // A send to the same peer while the replacement pre-key is being published.
        val sendDuringPublish = AtomicReference<Result<EncryptedMessage>>()
        keyServer.onPublish = { uid ->
            if (uid == BOB) {
                sendDuringPublish.set(runCatching { withTimeout(LOCK_WAIT_MS) { bob.manager.encrypt(ALICE, "reply") } })
            }
        }

        assertEquals("hello", bob.manager.decrypt(ALICE, firstContact))

        val result = sendDuringPublish.get()
        assertNotNull("the consumed pre-key was never replenished", result)
        assertTrue("the send waited on the decrypt's session lock: ${result!!.exceptionOrNull()}", result.isSuccess)
        assertEquals("reply", alice.manager.decrypt(BOB, result.getOrThrow()))
    }

    // ── harness ─────────────────────────────────────────────────────────────

    private class Party(val manager: SignalManager, val dao: ObservedSignalDao)

    private fun party(uid: String): Party {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), SignalDatabase::class.java
        ).allowMainThreadQueries().build()
        databases += db
        val dao = ObservedSignalDao(db.signalDao())
        val auth = mockk<AuthSource> { every { currentUserId } returns uid }
        return Party(SignalManager(SignalProtocolStoreImpl(dao), keyServer, auth), dao)
    }

    private suspend fun registeredPair(): Pair<Party, Party> {
        val alice = party(ALICE)
        val bob = party(BOB)
        alice.manager.ensureInitialized()
        bob.manager.ensureInitialized()
        return alice to bob
    }

    /** Stands in for `keyBundles/{uid}`: what one party publishes, the other fetches. */
    private class FakeKeyServer : KeySource {
        private val bundles = ConcurrentHashMap<String, PreKeyBundle>()

        /** Runs inside [publishKeys], before the new bundle replaces the old one. */
        @Volatile
        var onPublish: (suspend (uid: String) -> Unit)? = null

        override suspend fun publishKeys(
            uid: String,
            identityKey: IdentityKey,
            registrationId: Int,
            signedPreKey: SignedPreKeyRecord,
            preKey: PreKeyRecord,
            kyberPreKey: KyberPreKeyRecord,
        ) {
            onPublish?.invoke(uid)
            bundles[uid] = PreKeyBundle(
                registrationId,
                1,
                preKey.id,
                preKey.keyPair.publicKey,
                signedPreKey.id,
                signedPreKey.keyPair.publicKey,
                signedPreKey.signature,
                identityKey,
                kyberPreKey.id,
                kyberPreKey.keyPair.publicKey,
                kyberPreKey.signature,
            )
        }

        override suspend fun fetchPreKeyBundle(uid: String): PreKeyBundle? = bundles[uid]
    }

    /**
     * A party's DAO with a hook in front of the session and trusted-identity
     * reads. Only session work — an encrypt's body, libsignal's decrypt — makes
     * them, so two threads inside the hook at once are two session operations
     * for that party interleaving.
     */
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

    /**
     * Holds the first thread to reach [hook] until released, and records whether
     * any other thread reached it in the meantime.
     */
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

        /** Waits for a holder, gives any interleaving call its window to show up, then lets go. */
        fun holdThenRelease() {
            assertTrue("no session operation started", entered.await(LOCK_WAIT_MS, TimeUnit.MILLISECONDS))
            Thread.sleep(INTERLEAVE_WINDOW_MS)
            released.countDown()
        }
    }
}
