package com.eeck.server.features.session

import com.eeck.server.core.ids.ChatId

/**
 * Storage for chat-link/session records, keyed by id. Implementations own
 * TTL expiry: `find()` must behave as if an entry never existed once it has
 * aged past the configured TTL.
 */
interface SessionStore {
    fun create(id: ChatId): SessionRecord
    fun find(id: ChatId): SessionRecord?
    fun markDeleted(id: ChatId): SessionRecord?
}
