package com.firestream.chat.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.firestream.chat.di.DatabaseModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Schema and migration harness for [AppDatabase] and [SignalDatabase].
 *
 * Every Room version bump from AppDatabase v35 and SignalDatabase v1 onward must
 * add an explicit Migration and a test here verifying both schema validity and
 * data preservation across the upgrade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val appHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @get:Rule
    val signalHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SignalDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `AppDatabase baseline v35 migrates cleanly to current version`() {
        val dbName = "app-migration-v35-test.db"
        appHelper.createDatabase(dbName, 35).close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            db.openHelper.writableDatabase
            assertEquals(AppDatabase.CURRENT_VERSION, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `SignalDatabase baseline v1 migrates cleanly to current version`() {
        val dbName = "signal-migration-v1-test.db"
        signalHelper.createDatabase(dbName, 1).close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(context, SignalDatabase::class.java, dbName)
            .addMigrations(*SignalDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            db.openHelper.writableDatabase
            assertEquals(SignalDatabase.CURRENT_VERSION, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `destructive fallback is limited to legacy versions below 35`() {
        assertTrue(1 in AppDatabase.LEGACY_DESTRUCTIVE_VERSIONS)
        assertTrue(34 in AppDatabase.LEGACY_DESTRUCTIVE_VERSIONS)
        assertFalse(35 in AppDatabase.LEGACY_DESTRUCTIVE_VERSIONS)
        assertEquals(34, AppDatabase.LEGACY_DESTRUCTIVE_VERSIONS.size)
    }

    @Test
    fun `DatabaseModule upgrades legacy pre-v35 AppDatabase destructively`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("fire_stream_chat.db")
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.version = 34
        }

        val appDb = DatabaseModule.provideDatabase(context)
        try {
            assertEquals(AppDatabase.CURRENT_VERSION, appDb.openHelper.writableDatabase.version)
        } finally {
            appDb.close()
        }
    }

    @Test
    fun `DatabaseModule refuses destructive migration on unmigrated AppDatabase version`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("fire_stream_chat.db")
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.version = AppDatabase.CURRENT_VERSION + 1
        }

        val appDb = DatabaseModule.provideDatabase(context)
        try {
            assertThrows(IllegalStateException::class.java) {
                appDb.openHelper.writableDatabase
            }
        } finally {
            appDb.close()
        }
    }

    @Test
    fun `DatabaseModule refuses destructive migration on unmigrated SignalDatabase version`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("signal.db")
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.version = SignalDatabase.CURRENT_VERSION + 1
        }

        val signalDb = DatabaseModule.provideSignalDatabase(context)
        try {
            assertThrows(IllegalStateException::class.java) {
                signalDb.openHelper.writableDatabase
            }
        } finally {
            signalDb.close()
        }
    }
}
