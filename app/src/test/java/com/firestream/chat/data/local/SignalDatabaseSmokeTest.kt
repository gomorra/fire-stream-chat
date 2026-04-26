package com.firestream.chat.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.local.dao.SignalDao
import com.firestream.chat.data.local.entity.SignalIdentityEntity
import com.firestream.chat.data.local.entity.SignalKyberPreKeyEntity
import com.firestream.chat.data.local.entity.SignalPreKeyEntity
import com.firestream.chat.data.local.entity.SignalSenderKeyEntity
import com.firestream.chat.data.local.entity.SignalSessionEntity
import com.firestream.chat.data.local.entity.SignalSignedPreKeyEntity
import com.firestream.chat.data.local.entity.SignalTrustedIdentityEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the dedicated [SignalDatabase] (split out of [AppDatabase] in v19) wires the
 * seven Signal Protocol entities to [SignalDao] correctly: each entity round-trips through
 * its primary key. If a future change drops an entity from the database's `entities` list
 * or renames a table without updating the DAO, these tests fail at compile or first save.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class SignalDatabaseSmokeTest {

    private lateinit var db: SignalDatabase
    private lateinit var dao: SignalDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SignalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.signalDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `identity round-trips`() {
        val entity = SignalIdentityEntity(identityKeyPair = byteArrayOf(1, 2, 3), registrationId = 42)
        dao.saveIdentity(entity)

        assertEquals(entity, dao.getIdentity())
    }

    @Test
    fun `pre-key round-trips and respects contains and delete`() {
        dao.savePreKey(SignalPreKeyEntity(preKeyId = 7, record = byteArrayOf(9)))

        assertTrue(dao.containsPreKey(7))
        assertNotNull(dao.getPreKey(7))

        dao.deletePreKey(7)
        assertNull(dao.getPreKey(7))
        assertTrue(!dao.containsPreKey(7))
    }

    @Test
    fun `signed pre-key round-trips and lists all`() {
        dao.saveSignedPreKey(SignalSignedPreKeyEntity(signedPreKeyId = 1, record = byteArrayOf(1)))
        dao.saveSignedPreKey(SignalSignedPreKeyEntity(signedPreKeyId = 2, record = byteArrayOf(2)))

        assertEquals(2, dao.getAllSignedPreKeys().size)
        assertTrue(dao.containsSignedPreKey(1))

        dao.deleteSignedPreKey(1)
        assertEquals(1, dao.getAllSignedPreKeys().size)
    }

    @Test
    fun `session keyed by 'address' round-trips and prefix-queries by name`() {
        dao.saveSession(SignalSessionEntity(address = "alice:1", record = byteArrayOf(0xA)))
        dao.saveSession(SignalSessionEntity(address = "alice:2", record = byteArrayOf(0xB)))
        dao.saveSession(SignalSessionEntity(address = "bob:1", record = byteArrayOf(0xC)))

        assertEquals(2, dao.getSessionsByName("alice").size)
        assertTrue(dao.containsSession("bob:1"))

        dao.deleteAllSessionsByName("alice")
        assertEquals(0, dao.getSessionsByName("alice").size)
        assertNotNull(dao.getSession("bob:1"))
    }

    @Test
    fun `kyber pre-key round-trips`() {
        dao.saveKyberPreKey(SignalKyberPreKeyEntity(preKeyId = 99, record = byteArrayOf(7)))

        assertTrue(dao.containsKyberPreKey(99))
        assertEquals(1, dao.getAllKyberPreKeys().size)
    }

    @Test
    fun `sender key round-trips by composite address`() {
        val key = "alice:1:550e8400-e29b-41d4-a716-446655440000"
        dao.saveSenderKey(SignalSenderKeyEntity(key = key, record = byteArrayOf(1, 2)))

        assertNotNull(dao.getSenderKey(key))
        assertNull(dao.getSenderKey("unknown:1:00000000-0000-0000-0000-000000000000"))
    }

    @Test
    fun `trusted identity round-trips and supports delete`() {
        dao.saveTrustedIdentity(SignalTrustedIdentityEntity(address = "alice", identityKey = byteArrayOf(5)))

        assertNotNull(dao.getTrustedIdentity("alice"))

        dao.deleteTrustedIdentity("alice")
        assertNull(dao.getTrustedIdentity("alice"))
    }
}
