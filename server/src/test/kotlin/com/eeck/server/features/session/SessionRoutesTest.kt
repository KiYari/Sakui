package com.eeck.server.features.session

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Test-only clock whose [instant] can be advanced to simulate TTL expiry deterministically. */
private class AdjustableClock(
    var instant: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = AdjustableClock(instant, zone)
    override fun instant(): Instant = instant
}

private fun Application.testSessionModule(store: InMemorySessionStore) {
    install(ContentNegotiation) { json() }
    install(RateLimit) {
        register(CREATE_LINK_RATE_LIMIT) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
        }
    }
    val service = SessionService(store)
    routing {
        route("/api") {
            sessionRoutes(service)
        }
    }
}

class SessionRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun defaultModule(): Application.() -> Unit = { testSessionModule(InMemorySessionStore()) }

    @Test
    fun `create returns a well-formed link`() = testApplication {
        application(defaultModule())

        val response = client.post("/api/chat-link")

        assertEquals(HttpStatusCode.OK, response.status)
        val link = json.decodeFromString<LinkResponse>(response.bodyAsText())
        assertTrue(SessionIdGenerator.isValidFormat(link.hash), "hash '${link.hash}' does not match the expected format")
        assertEquals(false, link.expired)
        assertEquals(false, link.deleted)
    }

    @Test
    fun `create returns a unique id on every call`() = testApplication {
        application(defaultModule())

        val first = json.decodeFromString<LinkResponse>(client.post("/api/chat-link").bodyAsText())
        val second = json.decodeFromString<LinkResponse>(client.post("/api/chat-link").bodyAsText())

        assertTrue(first.hash != second.hash)
    }

    @Test
    fun `status of an active link is 200 ACTIVE`() = testApplication {
        application(defaultModule())

        val link = json.decodeFromString<LinkResponse>(client.post("/api/chat-link").bodyAsText())
        val response = client.get("/api/chat-link/status/${link.hash}")

        assertEquals(HttpStatusCode.OK, response.status)
        val status = json.decodeFromString<StatusResponse>(response.bodyAsText())
        assertEquals("ok", status.status)
        assertEquals("ACTIVE", status.state)
    }

    @Test
    fun `status of an unknown but well-formed id is 404 NOT_FOUND`() = testApplication {
        application(defaultModule())

        val neverCreatedId = SessionIdGenerator.generate()
        val response = client.get("/api/chat-link/status/${neverCreatedId.value}")

        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = json.decodeFromString<StatusErrorResponse>(response.bodyAsText())
        assertEquals("Invalid channel", error.error)
        assertEquals("NOT_FOUND", error.state)
    }

    @Test
    fun `status of a malformed id is 400`() = testApplication {
        application(defaultModule())

        val response = client.get("/api/chat-link/status/not-a-valid-id")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `status of a deleted link is 410 DELETED`() = testApplication {
        application(defaultModule())

        val link = json.decodeFromString<LinkResponse>(client.post("/api/chat-link").bodyAsText())
        client.delete("/api/chat-link/${link.hash}")

        val response = client.get("/api/chat-link/status/${link.hash}")

        assertEquals(HttpStatusCode.Gone, response.status)
        val error = json.decodeFromString<StatusErrorResponse>(response.bodyAsText())
        assertEquals("Channel deleted", error.error)
        assertEquals("DELETED", error.state)
    }

    @Test
    fun `delete of an active link succeeds`() = testApplication {
        application(defaultModule())

        val link = json.decodeFromString<LinkResponse>(client.post("/api/chat-link").bodyAsText())
        val response = client.delete("/api/chat-link/${link.hash}")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<DeleteResponse>(response.bodyAsText())
        assertEquals("ok", body.status)
    }

    @Test
    fun `delete of an unknown id is 404`() = testApplication {
        application(defaultModule())

        val response = client.delete("/api/chat-link/${SessionIdGenerator.generate().value}")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `delete of an already-deleted link is 404`() = testApplication {
        application(defaultModule())

        val link = json.decodeFromString<LinkResponse>(client.post("/api/chat-link").bodyAsText())
        client.delete("/api/chat-link/${link.hash}")

        val response = client.delete("/api/chat-link/${link.hash}")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `delete of a malformed id is 400`() = testApplication {
        application(defaultModule())

        val response = client.delete("/api/chat-link/nope")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `status of a TTL-expired link is 404 NOT_FOUND`() = testApplication {
        val clock = AdjustableClock(Instant.parse("2024-01-01T00:00:00Z"))
        val store = InMemorySessionStore(SessionStoreConfig(ttl = Duration.ofHours(24)), clock)
        application { testSessionModule(store) }

        val link = json.decodeFromString<LinkResponse>(client.post("/api/chat-link").bodyAsText())
        clock.instant = clock.instant.plus(Duration.ofHours(25))

        val response = client.get("/api/chat-link/status/${link.hash}")

        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = json.decodeFromString<StatusErrorResponse>(response.bodyAsText())
        assertEquals("NOT_FOUND", error.state)
    }
}
