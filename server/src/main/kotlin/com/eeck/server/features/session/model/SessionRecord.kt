package com.eeck.server.features.session.model

import com.eeck.server.core.ids.ChatId
import java.time.Instant

/**
 * Internal record for a chat-link/session. Never serialized directly to the
 * wire — REST responses are built from this by the `dto` layer.
 */
data class SessionRecord(
    val id: ChatId,
    val deleted: Boolean,
    val createdAt: Instant,
)
