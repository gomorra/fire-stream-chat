package com.firestream.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import javax.inject.Inject

/**
 * Regression test for `:restart` process startup crash after backup restore.
 *
 * `RestartActivity` runs in an isolated `:restart` process where
 * `FirebaseInitProvider` does not run. Android still instantiates
 * [FireStreamApp] in that process, and `Hilt_FireStreamApp.onCreate()` executes
 * `hiltInternalInject()` *before* `FireStreamApp.onCreate()` can return early
 * on `getProcessName().endsWith(":restart")`.
 *
 * Keeping every `@Inject` field on [FireStreamApp] wrapped in [dagger.Lazy]
 * ensures `hiltInternalInject()` binds providers without instantiating
 * FirebaseFirestore, Room, or WorkManager in `:restart`.
 */
class FireStreamAppRestartProcessTest {

    @Test
    fun `every @Inject field on FireStreamApp is dagger Lazy so restart process never eagerly initializes Firebase`() {
        val eagerInjectFields = FireStreamApp::class.java.declaredFields
            .filter { it.isAnnotationPresent(Inject::class.java) }
            .filter { it.type != dagger.Lazy::class.java }
            .map { "${it.name}: ${it.type.name}" }

        assertTrue(
            "FireStreamApp @Inject fields must be dagger.Lazy<T> to avoid eager Firebase/Room init " +
                "in the :restart process before onCreate() returns early, but found: $eagerInjectFields",
            eagerInjectFields.isEmpty(),
        )
    }
}
