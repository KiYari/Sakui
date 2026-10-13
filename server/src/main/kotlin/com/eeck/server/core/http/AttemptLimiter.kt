package com.eeck.server.core.http

import java.util.concurrent.ConcurrentHashMap

/** Above this many tracked clients, stale entries are swept so the table cannot be grown without bound. */
private const val SWEEP_THRESHOLD = 4_096

/**
 * Slows down a client that keeps getting a secret wrong: after [limit] failures
 * inside [windowMs] it is refused until the oldest one ages out. Counting is per
 * client address, so one guesser cannot lock others out — except where many users
 * share an address (a proxy without `EECK_TRUST_PROXY`), which is why the key is
 * only as strong as its length.
 */
class AttemptLimiter(
    private val limit: Int = 10,
    private val windowMs: Long = 60_000L,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val failures = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun isThrottled(client: String): Boolean {
        val log = failures[client] ?: return false
        synchronized(log) {
            prune(log)
            return log.size >= limit
        }
    }

    fun recordFailure(client: String) {
        if (failures.size > SWEEP_THRESHOLD) sweep()
        val log = failures.computeIfAbsent(client) { ArrayDeque() }
        synchronized(log) { log.addLast(nowMillis()) }
    }

    private fun prune(log: ArrayDeque<Long>) {
        val cutoff = nowMillis() - windowMs
        while (log.isNotEmpty() && log.first() < cutoff) log.removeFirst()
    }

    private fun sweep() {
        for ((client, log) in failures) {
            val empty = synchronized(log) {
                prune(log)
                log.isEmpty()
            }
            if (empty) failures.remove(client, log)
        }
    }
}
