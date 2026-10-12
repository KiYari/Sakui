package com.eeck.server.app

import com.eeck.server.core.config.ServerConfig
import com.eeck.server.core.http.ACCESS_KEY_HEADER
import com.eeck.server.core.http.AccessKeyGuard
import com.eeck.server.core.http.AccessVerdict
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.close
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class AccessKeyTest {

    private fun locked() = AppModule(ServerConfig(accessKeys = listOf("old-key", "new-key")))

    @Test
    fun `an open server needs no key`() = testApplication {
        application { module(AppModule(ServerConfig(accessKeys = emptyList()))) }

        assertEquals(HttpStatusCode.OK, client.post("/api/chat-link").status)
        assertTrue(client.get("/api/health").bodyAsText().contains("\"keyRequired\":false"))
    }

    @Test
    fun `health stays public and says a key is required`() = testApplication {
        application { module(locked()) }

        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"keyRequired\":true"))
    }

    @Test
    fun `the api refuses a request with no key`() = testApplication {
        application { module(locked()) }

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/chat-link").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/access").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/chat-link/status/abc").status)
    }

    @Test
    fun `a wrong key is refused`() = testApplication {
        application { module(locked()) }

        val response = client.post("/api/chat-link") { header(ACCESS_KEY_HEADER, "guess") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `any configured key is accepted, so keys can be rotated`() = testApplication {
        application { module(locked()) }

        for (key in listOf("old-key", "new-key")) {
            assertEquals(HttpStatusCode.OK, client.post("/api/chat-link") { header(ACCESS_KEY_HEADER, key) }.status)
        }
        assertEquals(HttpStatusCode.OK, client.get("/api/access") { header(ACCESS_KEY_HEADER, "new-key") }.status)
    }

    @Test
    fun `a key that was removed no longer works`() = testApplication {
        application { module(AppModule(ServerConfig(accessKeys = listOf("new-key")))) }

        // What a client still holding the old key sees after the server was restarted with a new one.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/access") { header(ACCESS_KEY_HEADER, "old-key") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/access") { header(ACCESS_KEY_HEADER, "new-key") }.status)
    }

    @Test
    fun `a websocket upgrade without a valid key is refused by the server`() = testApplication {
        application { module(locked()) }
        val ws = createClient { install(ClientWebSockets) }

        for (path in listOf("/ws?chatId=abc", "/ws?chatId=abc&key=guess", "/ws?chatId=abc&key=")) {
            // The test engine does not expose the handshake status, but the next test opens the very same
            // URL with a valid key and succeeds, so the key is the only thing that differs.
            assertFailsWith<Exception>(path) { ws.webSocketSession(path) }
        }
    }

    @Test
    fun `a websocket upgrade with a valid key gets through the gate`() = testApplication {
        application { module(locked()) }
        val ws = createClient { install(ClientWebSockets) }

        // Opening succeeds; what the chat handler does next is covered by the chat tests.
        ws.webSocketSession("/ws?chatId=abc&key=new-key").close()
    }

    @Test
    fun `path tricks cannot get around the gate`() = testApplication {
        application { module(locked()) }

        for (path in listOf("/api/chat-link/", "/api//chat-link", "/api/./chat-link", "/x/../api/chat-link", "/api/%63hat-link")) {
            val status = client.post(path).status
            assertTrue(status != HttpStatusCode.OK, "$path answered $status without a key")
        }
        val ws = createClient { install(ClientWebSockets) }
        for (path in listOf("/ws/?chatId=a", "/x/../ws?chatId=a", "//ws?chatId=a")) {
            assertTrue(runCatching { ws.webSocketSession(path) }.isFailure, "$path upgraded without a key")
        }
    }

    @Test
    fun `the key in the query string is honoured on the websocket route only`() = testApplication {
        application { module(locked()) }

        // REST must use the header: a query string is the likelier one to land in a log.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/chat-link?key=old-key").status)
    }

    @Test
    fun `an empty key never counts as a key`() = testApplication {
        application { module(locked()) }

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/chat-link") { header(ACCESS_KEY_HEADER, "") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/chat-link?key=").status)
    }

    @Test
    fun `repeated wrong keys are slowed down, across routes`() = testApplication {
        application { module(locked()) }
        val ws = createClient { install(ClientWebSockets) }

        repeat(5) { client.post("/api/chat-link") { header(ACCESS_KEY_HEADER, "guess$it") } }
        repeat(5) { runCatching { ws.webSocketSession("/ws?chatId=a&key=guess$it") } }
        // One shared budget per client: even the right key waits out the window once it is spent.
        assertEquals(HttpStatusCode.TooManyRequests, client.post("/api/chat-link") { header(ACCESS_KEY_HEADER, "old-key") }.status)
    }

    @Test
    fun `the failure budget recovers after the window and stays bounded`() {
        var now = 0L
        val guard = AccessKeyGuard(listOf("k"), nowMillis = { now })

        repeat(10) { assertEquals(AccessVerdict.Wrong, guard.check("a", "bad")) }
        assertEquals(AccessVerdict.Throttled, guard.check("a", "k"))
        assertEquals(AccessVerdict.Allowed, guard.check("b", "k"), "another client is unaffected")

        now += 61_000
        assertEquals(AccessVerdict.Allowed, guard.check("a", "k"))

        // Many one-off clients must not grow the table forever.
        repeat(6_000) { guard.check("c$it", "bad") }
        now += 61_000
        guard.check("trigger-sweep", "bad")
        assertEquals(AccessVerdict.Allowed, guard.check("c1", "k"))
    }

    @Test
    fun `a key with non-latin characters works, sent percent-encoded the way the web client sends it`() = testApplication {
        application { module(AppModule(ServerConfig(accessKeys = listOf("пароль-секрет")))) }
        val ws = createClient { install(ClientWebSockets) }

        val encoded = java.net.URLEncoder.encode("пароль-секрет", Charsets.UTF_8).replace("+", "%20")
        assertEquals(HttpStatusCode.OK, client.post("/api/chat-link") { header(ACCESS_KEY_HEADER, encoded) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/chat-link") { header(ACCESS_KEY_HEADER, "wrong") }.status)
        // On the socket the key is an ordinary (already decoded by the framework) query parameter.
        ws.webSocketSession("/ws?chatId=abc&key=$encoded").close()
    }

    @Test
    fun `no keys configured means everything is allowed`() {
        val guard = AccessKeyGuard(emptyList())
        assertEquals(AccessVerdict.Allowed, guard.check("a", null))
        assertEquals(false, guard.required)
    }
}
