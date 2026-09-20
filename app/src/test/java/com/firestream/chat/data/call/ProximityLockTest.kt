package com.firestream.chat.data.call

import android.os.PowerManager
import com.firestream.chat.domain.model.CallAudioRoute
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test

class ProximityLockTest {

    private val powerManager: PowerManager = mockk(relaxed = true)
    private val wakeLock: PowerManager.WakeLock = mockk(relaxed = true)

    private lateinit var proximityLock: ProximityLock

    @Before
    fun setUp() {
        every { powerManager.newWakeLock(any(), any()) } returns wakeLock
        every { wakeLock.isHeld } returns true
        proximityLock = ProximityLock(powerManager)
    }

    @Test
    fun `the earpiece holds the lock`() {
        proximityLock.follow(CallAudioRoute.EARPIECE)

        verify { powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, any()) }
        verify { wakeLock.acquire(any()) }
    }

    @Test
    fun `staying on the earpiece does not stack wake locks`() {
        proximityLock.follow(CallAudioRoute.EARPIECE)
        proximityLock.follow(CallAudioRoute.EARPIECE)

        verify(exactly = 1) { wakeLock.acquire(any()) }
    }

    @Test
    fun `every other route releases the lock`() {
        proximityLock.follow(CallAudioRoute.EARPIECE)

        proximityLock.follow(CallAudioRoute.BLUETOOTH)

        verify(exactly = 1) { wakeLock.release() }
    }

    @Test
    fun `a route that never touches the earpiece never takes a lock`() {
        proximityLock.follow(CallAudioRoute.SPEAKER)
        proximityLock.follow(CallAudioRoute.WIRED_HEADSET)

        verify(exactly = 0) { powerManager.newWakeLock(any(), any()) }
        verify(exactly = 0) { wakeLock.release() }
    }

    @Test
    fun `a lock that timed out is taken again`() {
        proximityLock.follow(CallAudioRoute.EARPIECE)
        // PowerManager released it on its own after MAX_HOLD_MS; the call is still on the earpiece.
        every { wakeLock.isHeld } returns false

        proximityLock.follow(CallAudioRoute.EARPIECE)

        verify(exactly = 2) { wakeLock.acquire(any()) }
    }

    @Test
    fun `shutdown releases and latches`() {
        proximityLock.follow(CallAudioRoute.EARPIECE)

        proximityLock.shutdown()
        // The route collector's last emission, already in flight when the call ended.
        proximityLock.follow(CallAudioRoute.EARPIECE)

        verify(exactly = 1) { wakeLock.release() }
        verify(exactly = 1) { wakeLock.acquire(any()) }
    }

    @Test
    fun `shutdown without a held lock is a no-op, twice over`() {
        proximityLock.shutdown()
        proximityLock.shutdown()

        verify(exactly = 0) { wakeLock.release() }
    }
}
