package com.eeck.server.realtime

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

private fun Application.testRealtimeModule(registry: ChatRoomRegistry) {
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
    }
    routing {
        chatWebSocket(registry)
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
    fun `a third participant is rejected with an error frame and a specific close reason`() = testApplication {
        val registry = ChatRoomRegistry()
        application { testRealtimeModule(registry) }
        val ws = createClient { install(ClientWebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(Json) } }
        val chatId = "room-full"

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

            var errorFrame: ErrorFrame? = null
            var closeCode: Short? = null
            ws.webSocket("/ws?chatId=$chatId&userId=carol") {
                errorFrame = receiveDeserialized<OutboundFrame>() as ErrorFrame
                closeCode = closeReason.await()?.code
            }
            assertEquals("ROOM_FULL", errorFrame?.code)
            assertEquals(ChatCloseReasons.ROOM_FULL.code, closeCode)

            releaseAlice.complete(Unit)
            releaseBob.complete(Unit)
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

            assertTrue(registry.hasRoom(chatId))

            releaseAlice.complete(Unit)
            releaseBob.complete(Unit)
        }

        awaitCondition { !registry.hasRoom(chatId) }
    }
}
