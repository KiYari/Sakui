package com.eeck.server.features.session

import com.eeck.server.core.errors.DomainError
import com.eeck.server.core.errors.Outcome
import com.eeck.server.core.ids.ChatId
import com.eeck.server.features.chat.SessionLookup

/**
 * Business logic for chat-link lifecycle. Routes never talk to [SessionStore]
 * directly — this is the only layer that decides what a session's state
 * means.
 *
 * Also implements [SessionLookup] — the one interface `features/chat` declares
 * and `features/session` provides, so the realtime feature can check a chatId
 * belongs to a still-active link without depending on this package directly.
 */
class SessionService(private val store: SessionStore) : SessionLookup {

    fun createLink(): SessionRecord = store.create(SessionIdGenerator.generate())

    fun status(id: ChatId): Outcome<SessionRecord> {
        val session = store.find(id) ?: return Outcome.Failure(DomainError.SessionNotFound)
        return if (session.deleted) Outcome.Failure(DomainError.LinkAlreadyDeleted) else Outcome.Success(session)
    }

    fun delete(id: ChatId): Outcome<Unit> {
        val session = store.find(id) ?: return Outcome.Failure(DomainError.SessionNotFound)
        if (session.deleted) return Outcome.Failure(DomainError.SessionNotFound)
        store.markDeleted(id)
        return Outcome.Success(Unit)
    }

    override fun isActive(chatId: ChatId): Boolean = status(chatId) is Outcome.Success
}
