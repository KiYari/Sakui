package com.eeck.server.app

import com.eeck.server.core.config.ServerConfig
import com.eeck.server.core.ws.ConnectionLimiter
import com.eeck.server.core.ws.MAX_CONNECTIONS_PER_CLIENT
import com.eeck.server.features.chat.service.ChatRoomRegistry
import com.eeck.server.features.session.service.SessionService
import com.eeck.server.features.session.store.InMemorySessionStore
import com.eeck.server.features.session.store.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency graph. A DI framework (Koin, etc.) would be pure
 * overhead for the handful of objects this app actually has — this one
 * class is easier to read and adds no runtime magic.
 */
class AppModule(val config: ServerConfig = ServerConfig()) {
    /** Owns every [com.eeck.server.features.chat.service.ChatRoomActor] coroutine in the process. */
    private val roomActorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val chatRoomRegistry = ChatRoomRegistry(roomActorScope)
    val chatConnectionLimiter = ConnectionLimiter(MAX_CONNECTIONS_PER_CLIENT)

    val sessionStore: SessionStore = InMemorySessionStore()

    /** Deleting a link also ends its live room — the two features meet only here. */
    val sessionService = SessionService(sessionStore, onLinkDeleted = chatRoomRegistry::closeRoom)
}
