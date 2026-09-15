package com.eeck.server.features.chat.port

import com.eeck.server.core.ids.ChatId

/**
 * The one seam between `features/chat` and `features/session`: chat needs to
 * know whether a chatId belongs to a still-active link before letting a
 * WebSocket join, but must not depend on the session feature's package to
 * find that out. Declared here (the consumer), implemented by
 * `features/session/service/SessionService` (the provider) — delete
 * `features/chat` and `features/session` still compiles; delete
 * `features/session` and only this one interface (plus its wiring in
 * `app/AppModule`) needs a new home.
 */
fun interface SessionLookup {
    fun isActive(chatId: ChatId): Boolean
}
