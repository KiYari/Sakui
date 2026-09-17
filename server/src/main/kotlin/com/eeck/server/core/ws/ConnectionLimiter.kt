package com.eeck.server.core.ws

import java.util.concurrent.ConcurrentHashMap

/** Counts open connections per client key; thread-safe, shared by every connection of a route. */
class ConnectionLimiter(private val maxPerKey: Int) {
    private val open = ConcurrentHashMap<String, Int>()

    /** False means the key is at its limit and nothing was reserved. */
    fun tryAcquire(key: String): Boolean {
        var acquired = false
        open.compute(key) { _, current ->
            val count = current ?: 0
            if (count >= maxPerKey) {
                count
            } else {
                acquired = true
                count + 1
            }
        }
        return acquired
    }

    fun release(key: String) {
        // Drop the entry at zero so the map doesn't keep one row per address ever seen.
        open.computeIfPresent(key) { _, count -> if (count <= 1) null else count - 1 }
    }

    fun openCount(key: String): Int = open[key] ?: 0
}
