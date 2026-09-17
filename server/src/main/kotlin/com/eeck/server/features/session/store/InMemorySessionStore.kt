package com.eeck.server.features.session.store

import com.eeck.server.core.ids.ChatId
import com.eeck.server.features.session.model.SessionRecord
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory [SessionStore]. An entry older than [SessionStoreConfig.ttl]
 * behaves as absent from [find]/[markDeleted] and is evicted when read.
 *
 * Lazy eviction alone would leak: a link that is created and never opened is
 * never read again, so a client creating links steadily would grow the map for
 * as long as the process lives. [create] therefore also sweeps the whole map,
 * at most once per [SessionStoreConfig.sweepInterval] — growth only happens
 * through [create], so that is where the cost belongs, and no timer is needed.
 *
 * [clock] is injected so tests can fast-forward past the TTL deterministically
 * instead of sleeping.
 */
class InMemorySessionStore(
    private val config: SessionStoreConfig = SessionStoreConfig(),
    private val clock: Clock = Clock.systemUTC(),
) : SessionStore {

    private val sessions = ConcurrentHashMap<ChatId, SessionRecord>()
    private val lastSweep = AtomicReference(Instant.now(clock))

    override fun create(id: ChatId, ownerTokenHash: String): SessionRecord {
        sweepIfDue()
        val session = SessionRecord(id = id, deleted = false, createdAt = Instant.now(clock), ownerTokenHash = ownerTokenHash)
        sessions[id] = session
        return session
    }

    override fun find(id: ChatId): SessionRecord? {
        val session = sessions[id] ?: return null
        if (isExpired(session)) {
            sessions.remove(id, session)
            return null
        }
        return session
    }

    override fun markDeleted(id: ChatId): SessionRecord? =
        sessions.compute(id) { _, existing ->
            if (existing == null || isExpired(existing)) null else existing.copy(deleted = true)
        }

    /** Entries physically held, expired or not — lets tests assert the sweep actually frees memory. */
    fun storedCount(): Int = sessions.size

    private fun sweepIfDue() {
        val now = Instant.now(clock)
        val previous = lastSweep.get()
        if (now.isBefore(previous.plus(config.sweepInterval))) return
        // Only the caller that wins the CAS sweeps; concurrent creates skip it.
        if (!lastSweep.compareAndSet(previous, now)) return
        sessions.entries.removeIf { isExpired(it.value) }
    }

    private fun isExpired(session: SessionRecord): Boolean =
        Instant.now(clock).isAfter(session.createdAt.plus(config.ttl))
}
