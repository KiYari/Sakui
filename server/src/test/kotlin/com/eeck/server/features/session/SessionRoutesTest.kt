package com.eeck.server.features.session

import com.eeck.server.features.session.dto.DeleteResponse
import com.eeck.server.features.session.dto.LinkResponse
import com.eeck.server.features.session.dto.StatusErrorResponse
import com.eeck.server.core.ids.ChatId
import com.eeck.server.features.session.dto.StatusResponse
import com.eeck.server.features.session.model.OwnerToken
import com.eeck.server.features.session.model.SessionIdGenerator
import com.eeck.server.features.session.resource.CREATE_LINK_RATE_LIMIT
import com.eeck.server.features.session.resource.sessionRoutes
import com.eeck.server.features.session.service.SessionService
import com.eeck.server.features.session.store.InMemorySessionStore
import com.eeck.server.features.session.store.SessionStoreConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
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

private fun Application.testSessionModule(
    store: InMemorySessionStore,
    onLinkDeleted: (ChatId) -> Unit = {},
) {
    install(ContentNegotiation) { json() }
    install(RateLimit) {
        register(CREATE_LINK_RATE_LIMIT) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
        }
    }
    val service = SessionService(store, onLinkDeleted)
    routing {
        route("/api") {
            sessionRoutes(service)
        }
    }
}

private suspend fun HttpClient.deleteAsOwner(link: LinkResponse): HttpResponse =
    delete("/api/chat-link/${link.hash}") { bearerAuth(link.ownerToken) }

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
        client.deleteAsOwner(link)

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
        val response = client.deleteAsOwner(link)

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<DeleteResponse>(response.bodyAsText())
        assertEquals("ok", body.status)
    }

    @Test
    fun `the owner token is returned on create but only its hash is stored`() = testApplication {
        val store = InMemorySessionStore()
        application { testSessionModule(store) }

        val link = json.decodeFromString<LinkResponse>(client.post("/api/chat-link").bodyAsText())

        val record = store.find(ChatId(link.hash))!!
        assertTrue(link.ownerToken.length >= 43, "owner token should carry at least 256 bits")
        assertTrue(record.ownerTokenHash != link.ownerToken)
        assertTrue(OwnerToken.matches(link.ownerToken, record.ownerTokenHash))
    }

    @Test
    fun `delete without the owner token is 403 and leaves the link active`() = testApplication {
        application(defaultModule())

        val link = json.decodeFromString<LinkResponse>(client.post("/api/chat-link").bodyAsText())
        val response = client.delete("/api/chat-link/${link.hash}")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/chat-link/status/${link.hash}").status)
    }

    @Test
    fun `delete with a wrong token - including another link's valid token - is 403`() = testApplication {
        application(defaultModule())

        val link = json.decodeFromString<LinkResponse>(client.post("/api/chat-link").bodyAsText())
        val other = json.decodeFromString<LinkResponse>(client.post("/api/chat-link").bodyAsText())

        val guessed = client.delete("/api/chat-link/${link.hash}") { bearerAuth(OwnerToken.generate()) }
        val crossLink = client.delete("/api/chat-link/${link.hash}") { bearerAuth(other.ownerToken) }
        val chatIdAsToken = client.delete("/api/chat-link/${link.hash}") { bearerAuth(link.hash) }

        assertEquals(HttpStatusCode.Forbidden, guessed.status)
        assertEquals(HttpStatusCode.Forbidden, crossLink.status)
        assertEquals(HttpStatusCode.Forbidden, chatIdAsToken.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/chat-link/status/${link.hash}").status)
    }

    @Test
    fun `a successful delete notifies the listener exactly once, a refused one never`() = testApplication {
        val deleted = mutableListOf<ChatId>()
        application { testSessionModule(InMemorySessionStore()) { deleted += it } }

        val link = json.decodeFromString<LinkResponse>(client.post("/api/chat-link").bodyAsText())
        client.delete("/api/chat-link/${link.hash}")
        assertTrue(deleted.isEmpty())

        client.deleteAsOwner(link)
        client.deleteAsOwner(link)
        assertEquals(listOf(ChatId(link.hash)), deleted)
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
        client.deleteAsOwner(link)

        val response = client.deleteAsOwner(link)

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `delete of a malformed id is 400`() = testApplication {
        application(defaultModule())

        val response = client.delete("/api/chat-link/nope")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `expired links that are never read again are still swept out of memory`() = testApplication {
        val clock = AdjustableClock(Instant.parse("2024-01-01T00:00:00Z"))
        val store = InMemorySessionStore(SessionStoreConfig(ttl = Duration.ofHours(24)), clock)
        application { testSessionModule(store) }

        repeat(5) { client.post("/api/chat-link") }
        assertEquals(5, store.storedCount())

        clock.instant = clock.instant.plus(Duration.ofHours(25))
        val fresh = json.decodeFromString<LinkResponse>(client.post("/api/chat-link").bodyAsText())

        assertEquals(1, store.storedCount())
        assertEquals(HttpStatusCode.OK, client.get("/api/chat-link/status/${fresh.hash}").status)
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
