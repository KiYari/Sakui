package com.eeck.server.core.ws

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class FrameRateLimiterTest {

    private val clock = TestTimeSource()

    @Test
    fun `a burst up to capacity goes through without waiting`() {
        val limiter = FrameRateLimiter(capacity = 3, refillPerSecond = 1, timeSource = clock)

        repeat(3) { assertEquals(Duration.ZERO, limiter.reserve()) }
    }

    @Test
    fun `past the burst, each frame waits for the refill`() {
        val limiter = FrameRateLimiter(capacity = 2, refillPerSecond = 4, timeSource = clock)
        repeat(2) { limiter.reserve() }

        assertEquals(250.milliseconds, limiter.reserve())
    }

    @Test
    fun `a sustained flood is held at the refill rate instead of drifting past it`() {
        val limiter = FrameRateLimiter(capacity = 1, refillPerSecond = 10, timeSource = clock)
        limiter.reserve()

        // The caller obeys each wait, as the route does, and still never gets
        // more than the refill rate: every frame costs exactly 100ms.
        repeat(20) {
            val wait = limiter.reserve()
            assertEquals(100.milliseconds, wait)
            clock += wait
        }
    }

    @Test
    fun `an idle connection refills, but never beyond capacity`() {
        val limiter = FrameRateLimiter(capacity = 2, refillPerSecond = 1, timeSource = clock)
        repeat(2) { limiter.reserve() }

        clock += 60.seconds

        repeat(2) { assertEquals(Duration.ZERO, limiter.reserve()) }
        assertEquals(1.seconds, limiter.reserve())
    }
}
