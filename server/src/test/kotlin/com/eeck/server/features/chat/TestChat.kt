package com.eeck.server.features.chat

import com.eeck.server.core.ws.ConnectionLimiter
import com.eeck.server.core.ws.MAX_CONNECTIONS_PER_CLIENT
import com.eeck.server.features.chat.dto.Admission
import com.eeck.server.features.chat.dto.AdmissionStatus
import com.eeck.server.features.chat.dto.Admit
import com.eeck.server.features.chat.dto.InboundFrame
import com.eeck.server.features.chat.dto.JoinRequest
import com.eeck.server.features.chat.dto.OutboundFrame
import com.eeck.server.features.chat.dto.ParticipantJoined
import com.eeck.server.features.chat.port.SessionLookup
import com.eeck.server.features.chat.resource.chatWebSocket
import com.eeck.server.features.chat.service.ChatRoomRegistry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/** Permissive by default — most chat tests exercise room semantics, not link validation. */
fun Application.testChatModule(
    registry: ChatRoomRegistry,
    sessionLookup: SessionLookup = SessionLookup { true },
    connectionLimiter: ConnectionLimiter = ConnectionLimiter(MAX_CONNECTIONS_PER_CLIENT),
) {
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
    }
    routing {
        chatWebSocket(registry, sessionLookup, connectionLimiter = connectionLimiter)
    }
}

fun ApplicationTestBuilder.wsClient(): HttpClient =
    createClient { install(ClientWebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(Json) } }

suspend fun HttpClient.open(chatId: String): DefaultClientWebSocketSession = webSocketSession("/ws?chatId=$chatId")

/** Opens a socket, completes the handshake, and returns it with the admission decision the room sent first. */
suspend fun HttpClient.joinAs(chatId: String, identity: TestIdentity): Pair<DefaultClientWebSocketSession, Admission> {
    val session = open(chatId)
    session.authenticateAs(identity)
    return session to session.expect<Admission>()
}

suspend inline fun <reified T : OutboundFrame> DefaultClientWebSocketSession.expect(timeoutMs: Long = 5_000): T {
    val frame = withTimeout(timeoutMs) { receiveDeserialized<OutboundFrame>() }
    return frame as? T ?: fail("expected ${T::class.simpleName}, got $frame")
}

/** Asserts nothing arrives for a while. Silence can't be proven, only bounded — keep assertions of it paired with a positive control. */
suspend fun DefaultClientWebSocketSession.expectSilence(ms: Long = 300) {
    assertNull(withTimeoutOrNull(ms) { receiveDeserialized<OutboundFrame>() })
}

/**
 * Builds a room where [identities][0] is the host and everyone else has been
 * admitted, draining every frame the admissions produce so each returned
 * session starts with an empty inbox.
 */
suspend fun HttpClient.admittedRoom(chatId: String, vararg identities: TestIdentity): List<DefaultClientWebSocketSession> {
    val sessions = mutableListOf<DefaultClientWebSocketSession>()
    identities.forEachIndexed { index, identity ->
        val (session, admission) = joinAs(chatId, identity)
        if (index == 0) {
            assertEquals(Admission(AdmissionStatus.ADMITTED, identity.userId), admission)
        } else {
            val host = sessions[0]
            assertEquals(Admission(AdmissionStatus.PENDING, identities[0].userId), admission)
            assertEquals(JoinRequest(identity.userId), host.expect<JoinRequest>())
            host.sendSerialized<InboundFrame>(Admit(identity.userId))
            assertEquals(AdmissionStatus.ADMITTED, session.expect<Admission>().status)
            sessions.forEachIndexed { peerIndex, peer ->
                assertEquals(ParticipantJoined(identity.userId, announce = true), peer.expect<ParticipantJoined>())
                assertEquals(ParticipantJoined(identities[peerIndex].userId), session.expect<ParticipantJoined>())
            }
        }
        sessions += session
    }
    return sessions
}

suspend fun awaitCondition(timeoutMs: Long = 2_000, intervalMs: Long = 20, condition: () -> Boolean) {
    withTimeout(timeoutMs) {
        while (!condition()) delay(intervalMs)
    }
}
