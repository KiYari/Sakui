package com.eeck.server.features.auth.service

import com.eeck.server.features.auth.model.Tokens
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

enum class SessionKind { USER, ADMIN }

/** One notice to show on the admin page after a form post, once. */
data class Flash(val kind: Kind, val message: String, val code: String? = null) {
    enum class Kind { SUCCESS, ERROR, CODE }
}

class Session(
    val kind: SessionKind,
    val createdAtMillis: Long,
    @Volatile var expiresAtMillis: Long,
    /** Anti-forgery token for the admin forms; unused for user sessions. */
    val csrf: String,
) {
    val flash = AtomicReference<Flash?>(null)
}

/**
 * Sessions live in memory, keyed by the hash of their token, so a memory dump holds
 * nothing that can be replayed. A restart ends them all; people sign in again.
 *
 * Each use pushes the expiry out by [idleMillis], never past [maxMillis] after
 * creation — a stolen token cannot stay alive forever by being used.
 */
class SessionRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idleMillis: Long,
    private val maxMillis: Long,
) {
    private val sessions = ConcurrentHashMap<String, Session>()

    /** Returns the new token; the registry itself never keeps it. */
    fun create(kind: SessionKind): Pair<String, Session> {
        sweep()
        val now = clock()
        val token = Tokens.random()
        val session = Session(kind, now, now + minOf(idleMillis, maxMillis), Tokens.random())
        sessions[Tokens.sha256Hex(token)] = session
        return token to session
    }

    /** The live session for [token], with its expiry extended, or null. */
    fun find(token: String): Session? {
        val key = Tokens.sha256Hex(token)
        val session = sessions[key] ?: return null
        val now = clock()
        if (session.expiresAtMillis <= now) {
            sessions.remove(key, session)
            return null
        }
        session.expiresAtMillis = minOf(now + idleMillis, session.createdAtMillis + maxMillis)
        return session
    }

    fun revoke(token: String) {
        sessions.remove(Tokens.sha256Hex(token))
    }

    /** Ends every session of [kind]; the number ended. */
    fun revokeAll(kind: SessionKind): Int {
        val doomed = sessions.entries.filter { it.value.kind == kind }
        doomed.forEach { sessions.remove(it.key, it.value) }
        return doomed.size
    }

    fun count(kind: SessionKind): Int {
        sweep()
        return sessions.values.count { it.kind == kind }
    }

    private fun sweep() {
        val now = clock()
        sessions.entries.removeIf { it.value.expiresAtMillis <= now }
    }
}
