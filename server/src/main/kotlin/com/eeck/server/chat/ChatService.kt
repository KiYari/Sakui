package com.eeck.server.chat

import com.eeck.server.session.ChatSession
import com.eeck.server.session.SessionStore

sealed interface LinkStatusResult {
    object Active : LinkStatusResult
    object Deleted : LinkStatusResult
    object NotFound : LinkStatusResult
}

sealed interface DeleteLinkResult {
    object Deleted : DeleteLinkResult
    object NotFound : DeleteLinkResult
}

/**
 * Business logic for chat-link lifecycle. Routes never talk to [SessionStore]
 * directly — this is the only layer that decides what a session's state
 * means.
 */
class ChatService(private val store: SessionStore) {

    fun createLink(): ChatSession = store.create(ChatIdGenerator.generate())

    fun status(id: String): LinkStatusResult {
        val session = store.find(id) ?: return LinkStatusResult.NotFound
        return if (session.deleted) LinkStatusResult.Deleted else LinkStatusResult.Active
    }

    fun delete(id: String): DeleteLinkResult {
        val session = store.find(id) ?: return DeleteLinkResult.NotFound
        if (session.deleted) return DeleteLinkResult.NotFound
        store.markDeleted(id)
        return DeleteLinkResult.Deleted
    }
}
