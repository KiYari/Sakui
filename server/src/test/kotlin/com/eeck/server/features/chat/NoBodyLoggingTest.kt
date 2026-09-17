package com.eeck.server.features.chat

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.eeck.server.features.chat.dto.InboundFrame
import com.eeck.server.features.chat.dto.MessageIn
import com.eeck.server.features.chat.dto.MessageOut
import com.eeck.server.features.chat.service.ChatRoomRegistry
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Regression guard, not a one-off check: message bodies are opaque E2EE
 * ciphertext, but this proves it structurally — proves nothing ever logs
 * one, on every future change to this route, not just today.
 */
class NoBodyLoggingTest {

    @Test
    fun `message bodies never appear in application logs`() = testApplication {
        application {
            install(CallLogging)
            testChatModule(ChatRoomRegistry())
        }
        val secretMarker = "TEST-MARKER-${System.nanoTime()}"

        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        rootLogger.addAppender(appender)

        try {
            val (a, b) = wsClient().admittedRoom("room-log-test", TestIdentity.nth(0), TestIdentity.nth(1))
            b.sendSerialized<InboundFrame>(MessageIn(JsonPrimitive(secretMarker)))
            assertEquals(JsonPrimitive(secretMarker), a.expect<MessageOut>().body)
        } finally {
            rootLogger.detachAppender(appender)
        }

        val loggedText = appender.list.joinToString("\n") { it.formattedMessage ?: "" }
        assertFalse(loggedText.contains(secretMarker), "message body leaked into application logs")
    }
}
