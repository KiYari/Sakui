package com.eeck.server.features.chat

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertFalse
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

private fun Application.testLoggingModule(registry: ChatRoomRegistry) {
    install(CallLogging)
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
    }
    routing {
        chatWebSocket(registry, SessionLookup { true })
    }
}

/**
 * Regression guard, not a one-off check: message bodies are opaque E2EE
 * ciphertext, but this proves it structurally — proves nothing ever logs
 * one, on every future change to this route, not just today.
 */
class NoBodyLoggingTest {

    @Test
    fun `message bodies never appear in application logs`() = testApplication {
        val registry = ChatRoomRegistry()
        application { testLoggingModule(registry) }
        val ws = createClient { install(ClientWebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(Json) } }
        val chatId = "room-log-test"
        val secretMarker = "TEST-MARKER-${System.nanoTime()}"

        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        rootLogger.addAppender(appender)

        try {
            val aliceReady = CompletableDeferred<Unit>()
            val bobConfirmedJoined = CompletableDeferred<Unit>()
            val aliceReceivedMessage = CompletableDeferred<Unit>()

            coroutineScope {
                launch {
                    ws.webSocket("/ws?chatId=$chatId&userId=alice") {
                        aliceReady.complete(Unit)
                        receiveDeserialized<OutboundFrame>() // ParticipantJoined(bob)
                        bobConfirmedJoined.complete(Unit)
                        receiveDeserialized<OutboundFrame>() as MessageOut
                        aliceReceivedMessage.complete(Unit)
                    }
                }
                aliceReady.await()

                launch {
                    ws.webSocket("/ws?chatId=$chatId&userId=bob") {
                        bobConfirmedJoined.await()
                        sendSerialized<InboundFrame>(MessageIn(JsonPrimitive(secretMarker)))
                        aliceReceivedMessage.await()
                    }
                }
            }
        } finally {
            rootLogger.detachAppender(appender)
        }

        val loggedText = appender.list.joinToString("\n") { it.formattedMessage ?: "" }
        assertFalse(loggedText.contains(secretMarker), "message body leaked into application logs")
    }
}
