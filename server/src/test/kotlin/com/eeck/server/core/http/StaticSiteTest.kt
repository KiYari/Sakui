package com.eeck.server.core.http

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticSiteTest {

    @Test
    fun `mounts nothing when the directory does not exist, and reports that`() = testApplication {
        var mounted = true
        application {
            routing {
                mounted = spaStaticFiles(File("this-directory-does-not-exist"))
            }
        }
        val response = client.get("/")

        assertFalse(mounted)
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `serves files from the directory, and falls back to index-html for unknown paths`() = testApplication {
        val dir = createTempDirectory("eeck-web-dist").toFile()
        File(dir, "index.html").writeText("<title>eeck</title>")
        File(dir, "assets").mkdir()
        File(dir, "assets/app.js").writeText("console.log('hi')")

        application {
            routing {
                assertTrue(spaStaticFiles(dir))
            }
        }

        val root = client.get("/")
        assertEquals(HttpStatusCode.OK, root.status)
        assertTrue(root.bodyAsText().contains("<title>eeck</title>"))

        val asset = client.get("/assets/app.js")
        assertEquals(HttpStatusCode.OK, asset.status)
        assertTrue(asset.bodyAsText().contains("console.log"))

        // The SPA keeps state in ?chatId=, so every real path is "/" — but a hard
        // refresh on some other path (or a stray bookmark) must still boot the app.
        val unknown = client.get("/whatever/not-a-real-route")
        assertEquals(HttpStatusCode.OK, unknown.status)
        assertTrue(unknown.bodyAsText().contains("<title>eeck</title>"))
    }

    @Test
    fun `a literal route registered alongside it still takes priority over the catch-all`() = testApplication {
        val dir = createTempDirectory("eeck-web-dist").toFile()
        File(dir, "index.html").writeText("<title>eeck</title>")

        application {
            routing {
                route("/api") {
                    get("/health") {
                        call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
                    }
                }
                spaStaticFiles(dir)
            }
        }

        val health = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertEquals("""{"status":"ok"}""", health.bodyAsText())
    }

    @Test
    fun `cannot escape the served directory with encoded traversal sequences`() = testApplication {
        // A sibling of web-dist, exactly where a real release layout keeps the
        // server's own install (release/eeck/{web-dist,server}/) — if traversal
        // worked, this is what it would actually expose.
        val parent = createTempDirectory("eeck-release").toFile()
        val webDist = File(parent, "web-dist").apply { mkdir() }
        File(webDist, "index.html").writeText("<title>eeck</title>")
        val secret = File(parent, "secret.txt")
        secret.writeText("TOP-SECRET-CONTENT")

        application {
            routing {
                assertTrue(spaStaticFiles(webDist))
            }
        }

        // Percent-encoded so Ktor's client doesn't collapse the "../" before the
        // request is even sent — this exercises the server's own decoding and
        // path-containment check, not the HTTP client's URL normalisation.
        val attempts = listOf(
            "/..%2fsecret.txt",
            "/..%2f..%2f..%2fetc%2fpasswd",
            "/%2e%2e/secret.txt",
            "/assets/..%2f..%2fsecret.txt",
            "/..\\secret.txt",
        )
        for (path in attempts) {
            val response = client.get(path)
            val body = response.bodyAsText()
            assertFalse(body.contains("TOP-SECRET-CONTENT"), "leaked the sibling file via: $path")
            assertTrue(
                response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.OK,
                "unexpected status ${response.status} for: $path",
            )
            // An OK here must be the SPA fallback (index.html), never the secret file's content.
            if (response.status == HttpStatusCode.OK) {
                assertTrue(body.contains("<title>eeck</title>"), "expected the SPA fallback for: $path, got: $body")
            }
        }
    }
}
