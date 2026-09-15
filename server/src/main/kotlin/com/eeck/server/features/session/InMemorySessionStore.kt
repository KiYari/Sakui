package com.eeck.server.features.session

import com.eeck.server.core.ids.ChatId
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [SessionStore]. TTL is enforced lazily: an entry older than
 * [SessionStoreConfig.ttl] behaves as absent from [find]/[markDeleted] and is
 * opportunistically evicted from the backing map the first time it's read
 * past expiry, rather than swept on a timer.
 *
 * [clock] is injected so tests can fast-forward past the TTL deterministically
 * instead of sleeping.
 */
class InMemorySessionStore(
    private val config: SessionStoreConfig = SessionStoreConfig(),
    private val clock: Clock = Clock.systemUTC(),
) : SessionStore {

    private val sessions = ConcurrentHashMap<ChatId, SessionRecord>()

    override fun create(id: ChatId): SessionRecord {
        val session = SessionRecord(id = id, deleted = false, createdAt = Instant.now(clock))
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

    private fun isExpired(session: SessionRecord): Boolean =
        Instant.now(clock).isAfter(session.createdAt.plus(config.ttl))
}
