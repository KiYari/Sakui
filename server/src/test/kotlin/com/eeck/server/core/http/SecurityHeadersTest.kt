package com.eeck.server.core.http

import io.ktor.client.request.get
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * This is the whole reason the app sets these itself rather than leaving it to
 * whatever sits in front: `build-release.bat`/`.sh` and the Caddy-fronted HTTPS
 * release have no nginx layer to add them, so if the app didn't, nothing would.
 */
class SecurityHeadersTest {

    @Test
    fun `every response carries the full header set, JSON and plain alike`() = testApplication {
        application {
            install(SecurityHeaders)
            routing {
                get("/anything") { call.respondText("ok") }
            }
        }

        val response = client.get("/anything")

        assertEquals(
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; " +
                "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
            response.headers["Content-Security-Policy"],
        )
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("same-origin", response.headers["Cross-Origin-Opener-Policy"])
        assertEquals("camera=(), microphone=(), geolocation=(), payment=()", response.headers["Permissions-Policy"])
    }

    @Test
    fun `a 404 still carries the headers - they guard the page, not one handler`() = testApplication {
        application {
            install(SecurityHeaders)
            routing { }
        }

        val response = client.get("/nothing-here")

        assertEquals("DENY", response.headers["X-Frame-Options"])
    }
}
