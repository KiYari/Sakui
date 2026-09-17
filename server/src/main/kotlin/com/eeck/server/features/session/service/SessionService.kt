package com.eeck.server.features.session.service

import com.eeck.server.core.errors.DomainError
import com.eeck.server.core.errors.Outcome
import com.eeck.server.core.ids.ChatId
import com.eeck.server.features.chat.port.SessionLookup
import com.eeck.server.features.session.model.CreatedLink
import com.eeck.server.features.session.model.OwnerToken
import com.eeck.server.features.session.model.SessionIdGenerator
import com.eeck.server.features.session.model.SessionRecord
import com.eeck.server.features.session.store.SessionStore

/**
 * Business logic for chat-link lifecycle. The resource layer never talks to
 * [SessionStore] directly — this is the only layer that decides what a
 * session's state means.
 *
 * Also implements [SessionLookup] — the one interface `features/chat` declares
 * and `features/session` provides, so the realtime feature can check a chatId
 * belongs to a still-active link without depending on this package directly.
 *
 * [onLinkDeleted] runs after a successful delete. It is a plain callback rather
 * than a dependency on the chat feature: `app/AppModule` wires it to close the
 * live room, so deleting a link ends the conversation instead of only stopping
 * new people from joining it.
 */
class SessionService(
    private val store: SessionStore,
    private val onLinkDeleted: (ChatId) -> Unit = {},
) : SessionLookup {

    fun createLink(): CreatedLink {
        val token = OwnerToken.generate()
        val record = store.create(SessionIdGenerator.generate(), OwnerToken.hash(token))
        return CreatedLink(record, token)
    }

    fun status(id: ChatId): Outcome<SessionRecord> {
        val session = store.find(id) ?: return Outcome.Failure(DomainError.SessionNotFound)
        return if (session.deleted) Outcome.Failure(DomainError.LinkAlreadyDeleted) else Outcome.Success(session)
    }

    fun delete(id: ChatId, ownerToken: String?): Outcome<Unit> {
        val session = store.find(id) ?: return Outcome.Failure(DomainError.SessionNotFound)
        if (session.deleted) return Outcome.Failure(DomainError.SessionNotFound)
        if (ownerToken == null || !OwnerToken.matches(ownerToken, session.ownerTokenHash)) {
            return Outcome.Failure(DomainError.NotLinkOwner)
        }
        store.markDeleted(id)
        onLinkDeleted(id)
        return Outcome.Success(Unit)
    }

    override fun isActive(chatId: ChatId): Boolean = status(chatId) is Outcome.Success
}
