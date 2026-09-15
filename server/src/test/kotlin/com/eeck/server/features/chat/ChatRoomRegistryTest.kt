package com.eeck.server.features.chat

import com.eeck.server.core.ids.ChatId
import com.eeck.server.features.chat.dto.OutboundFrame
import com.eeck.server.features.chat.dto.ParticipantJoined
import com.eeck.server.features.chat.port.SessionLookup
import com.eeck.server.features.chat.resource.chatWebSocket
import com.eeck.server.features.chat.service.ChatRoomRegistry
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

private fun Application.testRegistryModule(registry: ChatRoomRegistry) {
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
    }
    routing {
        chatWebSocket(registry, SessionLookup { true })
    }
}

private suspend fun awaitCondition(timeoutMs: Long = 2_000, intervalMs: Long = 20, condition: () -> Boolean) {
    withTimeout(timeoutMs) {
        while (!condition()) delay(intervalMs)
    }
}

/**
 * Targets exactly the race the actor is supposed to make structurally
 * impossible: many participants joining/leaving the same room's registry
 * entry at once, rather than one at a time like [ChatWebSocketTest].
 */
class ChatRoomRegistryTest {

    @Test
    fun `many concurrent joins to the same room all land in one live actor - no split-brain, no crash`() = testApplication {
        val registry = ChatRoomRegistry()
        application { testRegistryModule(registry) }
        val ws = createClient { install(ClientWebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(Json) } }
        val chatId = "room-stress"
        val participantCount = 40

        // For N joins processed by a single, unified room, the total ParticipantJoined
        // deliveries across everyone is exactly N*(N-1): each of the N-1 pairs generates
        // one "newcomer told about existing peer" and one "existing peer told about newcomer".
        // Any split into two or more actors for the same chatId would deliver strictly fewer -
        // cross-split pairs never notify each other. This is what actually distinguishes "one
        // room" from split-brain, not just the absence of an exception.
        val totalParticipantJoinedFrames = AtomicInteger(0)

        coroutineScope {
            val joins = (1..participantCount).map { i ->
                async {
                    ws.webSocket("/ws?chatId=$chatId&userId=user-$i") {
                        var localCount = 0
                        while (true) {
                            val frame = withTimeoutOrNull(300) { receiveDeserialized<OutboundFrame>() } ?: break
                            if (frame is ParticipantJoined) localCount++
                        }
                        totalParticipantJoinedFrames.addAndGet(localCount)
                    }
                }
            }
            joins.awaitAll()
        }

        assertEquals(participantCount * (participantCount - 1), totalParticipantJoinedFrames.get())
        awaitCondition { !registry.hasRoom(ChatId(chatId)) }
    }
}
