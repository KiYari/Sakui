package com.eeck.server.app

import com.eeck.server.core.config.ServerConfig
import com.eeck.server.features.chat.ChatRoomRegistry
import com.eeck.server.features.session.InMemorySessionStore
import com.eeck.server.features.session.SessionService
import com.eeck.server.features.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency graph. A DI framework (Koin, etc.) would be pure
 * overhead for the handful of objects this app actually has — this one
 * function is easier to read and adds no runtime magic.
 */
class AppModule(val config: ServerConfig = ServerConfig()) {
    val sessionStore: SessionStore = InMemorySessionStore()
    val sessionService = SessionService(sessionStore)

    /** Owns every [com.eeck.server.features.chat.ChatRoomActor] coroutine in the process. */
    private val roomActorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val chatRoomRegistry = ChatRoomRegistry(roomActorScope)
}
