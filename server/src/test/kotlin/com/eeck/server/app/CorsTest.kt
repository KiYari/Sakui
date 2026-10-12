package com.eeck.server.app

import com.eeck.server.core.config.ServerConfig
import com.eeck.server.core.config.parseHostList
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/** A web client hosted on another origin (e.g. Vercel) connecting directly is the only cross-origin caller. */
class CorsTest {

    private fun config() = ServerConfig(corsAllowedHosts = listOf("eeck.vercel.app", "localhost:5173"))

    @Test
    fun `an allowed https origin may create links`() = testApplication {
        application { module(AppModule(config())) }

        val response = client.post("/api/chat-link") { header(HttpHeaders.Origin, "https://eeck.vercel.app") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("https://eeck.vercel.app", response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `the delete preflight allows the owner token header`() = testApplication {
        application { module(AppModule(config())) }

        val response = client.options("/api/chat-link/abc") {
            header(HttpHeaders.Origin, "https://eeck.vercel.app")
            header(HttpHeaders.AccessControlRequestMethod, "DELETE")
            header(HttpHeaders.AccessControlRequestHeaders, "authorization")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `any other origin is refused`() = testApplication {
        application { module(AppModule(config())) }

        val response = client.post("/api/chat-link") { header(HttpHeaders.Origin, "https://evil.example") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `host lists are comma separated and tolerate stray spaces`() {
        assertEquals(listOf("a.example", "b.example:8443"), parseHostList(" a.example, ,b.example:8443 "))
    }
}
