package com.eeck.server.app

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.eeck.server.core.config.ServerConfig
import com.eeck.server.features.chat.TestIdentity
import com.eeck.server.features.chat.authenticateAs
import com.eeck.server.features.chat.dto.Admission
import com.eeck.server.features.chat.dto.ErrorFrame
import com.eeck.server.features.chat.expect
import com.eeck.server.features.chat.open
import com.eeck.server.features.chat.wsClient
import com.eeck.server.features.session.dto.LinkResponse
import com.eeck.server.features.session.model.SessionIdGenerator
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the real [module] wiring, where the per-client limits, log format and cross-feature hooks live. */
class SecurityWiringTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `link creation is rate limited per client, not globally`() = testApplication {
        application { module(AppModule(ServerConfig(trustProxy = true))) }

        repeat(10) {
            assertEquals(HttpStatusCode.OK, client.post("/api/chat-link") { header("X-Forwarded-For", "203.0.113.1") }.status)
        }
        val limited = client.post("/api/chat-link") { header("X-Forwarded-For", "203.0.113.1") }
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertTrue(limited.bodyAsText().contains("\"error\""), "429 carries a JSON error body clients can parse")
        assertEquals(
            HttpStatusCode.OK,
            client.post("/api/chat-link") { header("X-Forwarded-For", "203.0.113.2") }.status,
            "one noisy client must not lock everyone else out",
        )
    }

    @Test
    fun `status and delete share the same per-client budget as create, not an unmetered one`() = testApplication {
        application { module(AppModule(ServerConfig(trustProxy = true))) }
        val header = "203.0.113.5"

        // Spend the whole budget on GET and DELETE alone — if either endpoint were
        // exempt, this would leave room for a POST that must instead be refused.
        repeat(5) { client.get("/api/chat-link/status/${SessionIdGenerator.generate().value}") { header("X-Forwarded-For", header) } }
        repeat(5) { client.delete("/api/chat-link/${SessionIdGenerator.generate().value}") { header("X-Forwarded-For", header) } }

        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.post("/api/chat-link") { header("X-Forwarded-For", header) }.status,
        )
    }

    @Test
    fun `without a trusted proxy a forged forwarded address does not buy a fresh bucket`() = testApplication {
        application { module(AppModule(ServerConfig(trustProxy = false))) }

        repeat(10) { i -> client.post("/api/chat-link") { header("X-Forwarded-For", "198.51.100.$i") } }

        assertEquals(
            HttpStatusCode.TooManyRequests,
            client.post("/api/chat-link") { header("X-Forwarded-For", "198.51.100.99") }.status,
        )
    }

    @Test
    fun `deleting a link through the API disconnects its live room`() = testApplication {
        application { module(AppModule(ServerConfig())) }
        val link = json.decodeFromString<LinkResponse>(client.post("/api/chat-link").bodyAsText())

        val session = wsClient().open(link.hash)
        session.authenticateAs(TestIdentity.nth(0))
        session.expect<Admission>()

        assertEquals(HttpStatusCode.OK, client.delete("/api/chat-link/${link.hash}") { bearerAuth(link.ownerToken) }.status)

        assertEquals("LINK_DELETED", session.expect<ErrorFrame>().code)
    }

    @Test
    fun `chat ids and owner tokens never reach the logs`() = testApplication {
        application { module(AppModule(ServerConfig())) }

        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        rootLogger.addAppender(appender)

        val link = try {
            val link = json.decodeFromString<LinkResponse>(client.post("/api/chat-link").bodyAsText())
            client.get("/api/chat-link/status/${link.hash}")
            val session = wsClient().open(link.hash)
            session.authenticateAs(TestIdentity.nth(0))
            session.expect<Admission>()
            client.delete("/api/chat-link/${link.hash}") { bearerAuth(link.ownerToken) }
            session.expect<ErrorFrame>()
            link
        } finally {
            rootLogger.detachAppender(appender)
        }

        val loggedText = appender.list.joinToString("\n") { it.formattedMessage ?: "" }
        assertTrue(loggedText.contains("/api/chat-link/status/<redacted>"), "positive control: requests are still logged")
        assertFalse(loggedText.contains(link.hash), "chat id leaked into logs")
        assertFalse(loggedText.contains(link.ownerToken), "owner token leaked into logs")
    }
}
