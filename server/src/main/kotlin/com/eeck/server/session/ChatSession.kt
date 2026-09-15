package com.eeck.server.session

import java.time.Instant

/**
 * Internal record for a chat-link/session. Never serialized directly to the
 * wire — REST responses are built from this by the chat package's own DTOs.
 */
data class ChatSession(
    val id: String,
    val deleted: Boolean,
    val createdAt: Instant,
)
