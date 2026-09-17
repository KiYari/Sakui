package com.eeck.server.core.ws

import kotlin.math.min
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * Token bucket that answers "how long must this frame wait", instead of
 * rejecting it.
 *
 * Delaying rather than disconnecting is deliberate: the caller suspends before
 * reading the next frame, so the pressure travels back through TCP to the
 * sender. A client pushing a large file just slows down instead of losing its
 * connection mid-transfer, while a flooding client gets throttled to the
 * refill rate no matter how hard it pushes.
 *
 * Not thread-safe: one instance belongs to one connection's receive loop.
 */
class FrameRateLimiter(
    private val capacity: Int,
    private val refillPerSecond: Int,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    init {
        require(capacity > 0 && refillPerSecond > 0) { "capacity and refill rate must be positive" }
    }

    private var tokens = capacity.toDouble()
    private var lastRefill: ComparableTimeMark = timeSource.markNow()

    /**
     * Spends one token and returns how long to wait before handling the frame.
     * Tokens may go negative: that debt is exactly the wait time, so a sustained
     * flood is held at [refillPerSecond] rather than drifting past it.
     */
    fun reserve(): Duration {
        val now = timeSource.markNow()
        val elapsedSeconds = (now - lastRefill).toDouble(DurationUnit.SECONDS)
        lastRefill = now

        tokens = min(capacity.toDouble(), tokens + elapsedSeconds * refillPerSecond) - 1.0
        return if (tokens >= 0) Duration.ZERO else (-tokens / refillPerSecond).seconds
    }
}
