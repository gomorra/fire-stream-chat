package com.firestream.chat.data.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.firestream.chat.data.remote.source.SendErrorClassifier
import com.firestream.chat.data.remote.source.SendFailure
import com.firestream.chat.data.sticker.StickerNameRun
import com.firestream.chat.data.sticker.StickerNameSync
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** What a naming run's outcome tells WorkManager: come back, done, or do not come back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class StickerNameWorkerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val nameSync = mockk<StickerNameSync>()

    /** The network may pass. Everything else will not. */
    private val classifier = object : SendErrorClassifier {
        override fun classifyBackendError(error: Throwable): SendFailure? = null
        override fun classify(error: Throwable): SendFailure =
            if (error is IOException) SendFailure.TRANSIENT else SendFailure.PERMANENT
    }

    private fun worker(): StickerNameWorker =
        TestListenableWorkerBuilder<StickerNameWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = StickerNameWorker(appContext, workerParameters, nameSync, classifier)
            })
            .build()

    private suspend fun verdictOf(run: StickerNameRun): Result {
        coEvery { nameSync.run(any()) } returns run
        return worker().doWork()
    }

    @Test
    fun `a run with nothing left to wait for is done`() = runTest {
        assertEquals(Result.success(), verdictOf(StickerNameRun()))
    }

    @Test
    fun `requests without an answer bring the run back`() = runTest {
        assertEquals(Result.retry(), verdictOf(StickerNameRun(waiting = 3)))
        // Also beside a failure that will not pass: the answers are still worth coming back for.
        assertEquals(Result.retry(), verdictOf(StickerNameRun(waiting = 1, failures = listOf(IllegalStateException("no")))))
    }

    @Test
    fun `a failure that may pass brings the run back, and one that will not ends it`() = runTest {
        assertEquals(Result.retry(), verdictOf(StickerNameRun(failures = listOf(IllegalStateException("no"), IOException("offline")))))
        assertEquals(Result.failure(), verdictOf(StickerNameRun(failures = listOf(IllegalStateException("no")))))
    }

    @Test
    fun `a refusal ends the run without a retry, whatever else it holds`() = runTest {
        assertEquals(Result.failure(), verdictOf(StickerNameRun(waiting = 2, refused = true)))
    }

    @Test
    fun `a run that throws is sorted like a failure of its own`() = runTest {
        coEvery { nameSync.run(any()) } throws IOException("disk")
        assertEquals(Result.retry(), worker().doWork())

        coEvery { nameSync.run(any()) } throws IllegalStateException("no")
        assertEquals(Result.failure(), worker().doWork())
    }
}
