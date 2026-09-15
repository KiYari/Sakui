package com.eeck.server.features.chat

import com.eeck.server.core.ids.ChatId
import com.eeck.server.features.chat.dto.ErrorFrame
import com.eeck.server.features.chat.dto.InboundFrame
import com.eeck.server.features.chat.dto.MessageIn
import com.eeck.server.features.chat.dto.MessageOut
import com.eeck.server.features.chat.dto.OutboundFrame
import com.eeck.server.features.chat.dto.ParticipantLeft
import com.eeck.server.features.chat.port.SessionLookup
import com.eeck.server.features.chat.resource.ChatCloseReasons
import com.eeck.server.features.chat.resource.chatWebSocket
import com.eeck.server.features.chat.service.ChatRoomRegistry
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/** Permissive by default — these tests exercise relay/actor semantics, not link validation (see the rejection test below). */
private fun Application.testRealtimeModule(
    registry: ChatRoomRegistry,
    sessionLookup: SessionLookup = SessionLookup { true },
) {
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
    }
    routing {
        chatWebSocket(registry, sessionLookup)
    }
}

private suspend fun awaitCondition(timeoutMs: Long = 2_000, intervalMs: Long = 20, condition: () -> Boolean) {
    withTimeout(timeoutMs) {
        while (!condition()) delay(intervalMs)
    }
}

class ChatWebSocketTest {

    @Test
    fun `messages are relayed to the other participant unchanged`() = testApplication {
        val registry = ChatRoomRegistry()
        application { testRealtimeModule(registry) }
        val ws = createClient { install(ClientWebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(Json) } }
        val chatId = "room-msg"

        val aliceReady = CompletableDeferred<Unit>()
        val bobConfirmedJoined = CompletableDeferred<Unit>()
        val aliceReceivedMessage = CompletableDeferred<MessageOut>()
        val bobReceivedMessage = CompletableDeferred<MessageOut>()

        coroutineScope {
            launch {
                ws.webSocket("/ws?chatId=$chatId&userId=alice") {
                    aliceReady.complete(Unit)
                    receiveDeserialized<OutboundFrame>() // ParticipantJoined(bob)
                    bobConfirmedJoined.complete(Unit)
                    aliceReceivedMessage.complete(receiveDeserialized<OutboundFrame>() as MessageOut)
                    sendSerialized<InboundFrame>(MessageIn(JsonPrimitive("hi bob")))
                }
            }
            aliceReady.await()

            launch {
                ws.webSocket("/ws?chatId=$chatId&userId=bob") {
                    receiveDeserialized<OutboundFrame>() // ParticipantJoined(alice) - bob learns about the pre-existing peer
                    bobConfirmedJoined.await()
                    sendSerialized<InboundFrame>(MessageIn(JsonPrimitive("hi alice")))
                    bobReceivedMessage.complete(receiveDeserialized<OutboundFrame>() as MessageOut)
                }
            }
        }

        val toAlice = aliceReceivedMessage.await()
        assertEquals("bob", toAlice.sender)
        assertEquals(JsonPrimitive("hi alice"), toAlice.body)

        val toBob = bobReceivedMessage.await()
        assertEquals("alice", toBob.sender)
        assertEquals(JsonPrimitive("hi bob"), toBob.body)
    }

    @Test
    fun `a third participant joins the room and messages are relayed to both other participants`() = testApplication {
        val registry = ChatRoomRegistry()
        application { testRealtimeModule(registry) }
        val ws = createClient { install(ClientWebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(Json) } }
        val chatId = "room-three"

        val aliceReady = CompletableDeferred<Unit>()
        val bobConfirmedJoined = CompletableDeferred<Unit>()
        val carolConfirmedJoined = CompletableDeferred<Unit>()
        val releaseAlice = CompletableDeferred<Unit>()
        val releaseBob = CompletableDeferred<Unit>()
        val releaseCarol = CompletableDeferred<Unit>()
        val aliceReceivedFromCarol = CompletableDeferred<MessageOut>()
        val bobReceivedFromCarol = CompletableDeferred<MessageOut>()

        coroutineScope {
            launch {
                ws.webSocket("/ws?chatId=$chatId&userId=alice") {
                    aliceReady.complete(Unit)
                    receiveDeserialized<OutboundFrame>() // ParticipantJoined(bob)
                    bobConfirmedJoined.complete(Unit)
                    receiveDeserialized<OutboundFrame>() // ParticipantJoined(carol)
                    carolConfirmedJoined.complete(Unit)
                    aliceReceivedFromCarol.complete(receiveDeserialized<OutboundFrame>() as MessageOut)
                    releaseAlice.await()
                }
            }
            aliceReady.await()

            launch {
                ws.webSocket("/ws?chatId=$chatId&userId=bob") {
                    receiveDeserialized<OutboundFrame>() // ParticipantJoined(alice) - bob learns about the pre-existing peer
                    receiveDeserialized<OutboundFrame>() // ParticipantJoined(carol)
                    bobReceivedFromCarol.complete(receiveDeserialized<OutboundFrame>() as MessageOut)
                    releaseBob.await()
                }
            }
            bobConfirmedJoined.await()

            launch {
                ws.webSocket("/ws?chatId=$chatId&userId=carol") {
                    carolConfirmedJoined.complete(Unit)
                    sendSerialized<InboundFrame>(MessageIn(JsonPrimitive("hi both")))
                    releaseCarol.await()
                }
            }
            carolConfirmedJoined.await()

            val toAlice = aliceReceivedFromCarol.await()
            assertEquals("carol", toAlice.sender)
            assertEquals(JsonPrimitive("hi both"), toAlice.body)

            val toBob = bobReceivedFromCarol.await()
            assertEquals("carol", toBob.sender)
            assertEquals(JsonPrimitive("hi both"), toBob.body)

            releaseAlice.complete(Unit)
            releaseBob.complete(Unit)
            releaseCarol.complete(Unit)
        }
    }

    @Test
    fun `missing userId is rejected as a bad request`() = testApplication {
        val registry = ChatRoomRegistry()
        application { testRealtimeModule(registry) }
        val ws = createClient { install(ClientWebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(Json) } }

        var errorFrame: ErrorFrame? = null
        var closeCode: Short? = null
        ws.webSocket("/ws?chatId=room-x") {
            errorFrame = receiveDeserialized<OutboundFrame>() as ErrorFrame
            closeCode = closeReason.await()?.code
        }

        assertEquals("BAD_REQUEST", errorFrame?.code)
        assertEquals(ChatCloseReasons.BAD_REQUEST.code, closeCode)
    }

    @Test
    fun `missing chatId is rejected as a bad request`() = testApplication {
        val registry = ChatRoomRegistry()
        application { testRealtimeModule(registry) }
        val ws = createClient { install(ClientWebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(Json) } }

        var errorFrame: ErrorFrame? = null
        ws.webSocket("/ws?userId=alice") {
            errorFrame = receiveDeserialized<OutboundFrame>() as ErrorFrame
        }

        assertEquals("BAD_REQUEST", errorFrame?.code)
    }

    @Test
    fun `a chatId with no active session is rejected`() = testApplication {
        val registry = ChatRoomRegistry()
        application { testRealtimeModule(registry, sessionLookup = SessionLookup { false }) }
        val ws = createClient { install(ClientWebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(Json) } }

        var errorFrame: ErrorFrame? = null
        var closeCode: Short? = null
        ws.webSocket("/ws?chatId=never-created&userId=alice") {
            errorFrame = receiveDeserialized<OutboundFrame>() as ErrorFrame
            closeCode = closeReason.await()?.code
        }

        assertEquals("INVALID_SESSION", errorFrame?.code)
        assertEquals(ChatCloseReasons.INVALID_SESSION.code, closeCode)
        assertTrue(!registry.hasRoom(ChatId("never-created")))
    }

    @Test
    fun `the remaining participant is notified when the other disconnects`() = testApplication {
        val registry = ChatRoomRegistry()
        application { testRealtimeModule(registry) }
        val ws = createClient { install(ClientWebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(Json) } }
        val chatId = "room-leave"

        val aliceReady = CompletableDeferred<Unit>()
        val bobConfirmedJoined = CompletableDeferred<Unit>()
        val leftNotice = CompletableDeferred<ParticipantLeft>()

        coroutineScope {
            launch {
                ws.webSocket("/ws?chatId=$chatId&userId=alice") {
                    aliceReady.complete(Unit)
                    receiveDeserialized<OutboundFrame>() // ParticipantJoined(bob)
                    bobConfirmedJoined.complete(Unit)
                    leftNotice.complete(receiveDeserialized<OutboundFrame>() as ParticipantLeft)
                }
            }
            aliceReady.await()

            ws.webSocket("/ws?chatId=$chatId&userId=bob") {
                bobConfirmedJoined.await()
                // connect, let alice observe the join, then disconnect by returning
            }
        }

        val left = leftNotice.await()
        assertEquals("bob", left.userId)
    }

    @Test
    fun `the room is torn down once both participants disconnect`() = testApplication {
        val registry = ChatRoomRegistry()
        application { testRealtimeModule(registry) }
        val ws = createClient { install(ClientWebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(Json) } }
        val chatId = "room-cleanup"

        val aliceReady = CompletableDeferred<Unit>()
        val bobConfirmedJoined = CompletableDeferred<Unit>()
        val releaseAlice = CompletableDeferred<Unit>()
        val releaseBob = CompletableDeferred<Unit>()

        coroutineScope {
            launch {
                ws.webSocket("/ws?chatId=$chatId&userId=alice") {
                    aliceReady.complete(Unit)
                    receiveDeserialized<OutboundFrame>() // ParticipantJoined(bob)
                    bobConfirmedJoined.complete(Unit)
                    releaseAlice.await()
                }
            }
            aliceReady.await()

            launch {
                ws.webSocket("/ws?chatId=$chatId&userId=bob") {
                    releaseBob.await()
                }
            }
            bobConfirmedJoined.await()

            assertTrue(registry.hasRoom(ChatId(chatId)))

            releaseAlice.complete(Unit)
            releaseBob.complete(Unit)
        }

        awaitCondition { !registry.hasRoom(ChatId(chatId)) }
    }
}
