package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.MessageAvailability
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A notification / reminder tap jumps to one message. "Message no longer
 * available" must mean the backend confirmed it gone — not that the chat was
 * still syncing when a fixed wait ran out.
 */
class DeepLinkTargetTest {

    private val loaded = MutableStateFlow(false)
    private var availabilityChecks = 0

    private fun checker(answer: MessageAvailability): suspend () -> MessageAvailability = {
        availabilityChecks++
        answer
    }

    @Test
    fun `a target already loaded is found without asking the backend`() = runTest {
        loaded.value = true

        val outcome = awaitDeepLinkTarget(loaded, checker(MessageAvailability.GONE))

        assertEquals(DeepLinkTargetOutcome.FOUND, outcome)
        assertEquals(0, availabilityChecks)
    }

    // The reported bug: the target lands after the quick wait (cold start from a
    // notification) and used to be announced as gone first.
    @Test
    fun `a target still syncing past the quick wait is found, with no gone report`() = runTest {
        val outcome = async { awaitDeepLinkTarget(loaded, checker(MessageAvailability.PENDING)) }
        advanceTimeBy(3_000L + 5_000L)
        runCurrent()
        assertFalse(outcome.isCompleted)

        loaded.value = true
        runCurrent()

        assertEquals(DeepLinkTargetOutcome.FOUND, outcome.await())
    }

    @Test
    fun `a target the backend confirms gone is reported gone`() = runTest {
        assertEquals(
            DeepLinkTargetOutcome.GONE,
            awaitDeepLinkTarget(loaded, checker(MessageAvailability.GONE)),
        )
    }

    @Test
    fun `a target that lands while the backend is being asked is found`() = runTest {
        val outcome = awaitDeepLinkTarget(loaded, {
            loaded.value = true
            MessageAvailability.GONE
        })

        assertEquals(DeepLinkTargetOutcome.FOUND, outcome)
    }

    @Test
    fun `an unreachable backend never reports gone`() = runTest {
        assertEquals(
            DeepLinkTargetOutcome.NOT_ARRIVED,
            awaitDeepLinkTarget(loaded, checker(MessageAvailability.UNKNOWN)),
        )
    }

    @Test
    fun `a pending target that never lands gives up silently`() = runTest {
        assertEquals(
            DeepLinkTargetOutcome.NOT_ARRIVED,
            awaitDeepLinkTarget(loaded, checker(MessageAvailability.PENDING)),
        )
    }
}
