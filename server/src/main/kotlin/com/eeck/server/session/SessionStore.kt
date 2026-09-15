package com.eeck.server.session

/**
 * Storage for chat-link/session records, keyed by id. Implementations own
 * TTL expiry: `find()` must behave as if an entry never existed once it has
 * aged past the configured TTL.
 */
interface SessionStore {
    fun create(id: String): ChatSession
    fun find(id: String): ChatSession?
    fun markDeleted(id: String): ChatSession?
}
