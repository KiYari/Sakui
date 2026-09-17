package com.eeck.server.features.chat

import com.eeck.server.core.ids.ChatId
import com.eeck.server.core.ws.ConnectionLimiter
import com.eeck.server.features.chat.dto.AdmissionStatus
import com.eeck.server.features.chat.dto.Admit
import com.eeck.server.features.chat.dto.InboundFrame
import com.eeck.server.features.chat.dto.JoinRequest
import com.eeck.server.features.chat.dto.OutboundFrame
import com.eeck.server.features.chat.dto.ParticipantJoined
import com.eeck.server.features.chat.model.RoomLimits
import com.eeck.server.features.chat.service.ChatRoomRegistry
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.server.testing.testApplication
import io.ktor.websocket.close
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Targets exactly the race the actor is supposed to make structurally
 * impossible: many participants joining the same room's registry entry at
 * once, rather than one at a time like [ChatWebSocketTest].
 */
class ChatRoomRegistryTest {

    @Test
    fun `many concurrent joins to the same room all land in one live actor - no split-brain, no crash`() = testApplication {
        val participantCount = 40
        val registry = ChatRoomRegistry(limits = RoomLimits(maxMembers = 100, maxPending = 100))
        // Every test client shares one address, so the per-client cap has to make room for them.
        application { testChatModule(registry, connectionLimiter = ConnectionLimiter(100)) }
        val ws = wsClient()
        val chatId = "room-stress"
        // Distinct keys: participants sharing one would replace each other (see the replacement test).
        val identities = List(participantCount) { TestIdentity.nth(it) }

        val joined = coroutineScope {
            identities.map { identity -> async { ws.joinAs(chatId, identity) } }.awaitAll()
        }

        // Exactly one host, whoever the actor happened to process first. Two would mean two rooms.
        val hosts = joined.filter { it.second.status == AdmissionStatus.ADMITTED }
        assertEquals(1, hosts.size)
        val host = hosts.single().first

        // Once the host admits everyone, each pair has produced exactly two
        // ParticipantJoined deliveries (one each way, at the later admission), so the
        // total is N*(N-1). Any split into two or more actors for the same chatId
        // delivers strictly fewer — cross-split pairs never notify each other.
        val totals = coroutineScope {
            joined.map { (session, _) ->
                async {
                    var count = 0
                    while (true) {
                        val frame = withTimeoutOrNull(1_000) { session.receiveDeserialized<OutboundFrame>() } ?: break
                        when (frame) {
                            is ParticipantJoined -> count++
                            is JoinRequest -> if (session === host) session.sendSerialized<InboundFrame>(Admit(frame.userId))
                            else -> Unit
                        }
                    }
                    count
                }
            }.awaitAll()
        }

        assertEquals(participantCount * (participantCount - 1), totals.sum())
        joined.forEach { it.first.close() }
        awaitCondition { !registry.hasRoom(ChatId(chatId)) }
    }
}
