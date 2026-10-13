package com.eeck.server.features.auth

import com.eeck.server.app.AppModule
import com.eeck.server.app.module
import com.eeck.server.core.config.ServerConfig
import com.eeck.server.core.http.ACCESS_KEY_HEADER
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val MASTER = "master-key-for-the-panel"
private const val SERVER_PASSWORD = "the-server-password"

class AdminPanelTest {

    private fun config(admin: Boolean = true) = ServerConfig(
        accessKeys = listOf(SERVER_PASSWORD),
        masterKey = MASTER,
        adminEnabled = admin,
    )

    /** A client that does not follow redirects, so each step of the sign-in dance is visible. */
    private fun ApplicationTestBuilder.browser(): HttpClient = createClient { followRedirects = false }

    private fun HttpResponse.cookie(): String? =
        headers.getAll(HttpHeaders.SetCookie)?.firstOrNull { it.startsWith("eeck_admin=") }

    private fun HttpResponse.sessionCookieHeader(): String = cookie()!!.substringBefore(';')

    private suspend fun HttpClient.signIn(master: String = MASTER): HttpResponse =
        post("/admin/login") { setBody(FormDataContent(Parameters.build { append("masterKey", master) })) }

    private suspend fun HttpClient.page(cookie: String): String = get("/admin") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()

    private fun csrfOf(html: String): String =
        Regex("""name="_csrf"[^>]*value="([^"]+)"|value="([^"]+)"[^>]*name="_csrf"""").find(html)!!.let {
            it.groupValues[1].ifEmpty { it.groupValues[2] }
        }

    private suspend fun HttpClient.form(
        path: String,
        cookie: String,
        csrf: String?,
        vararg fields: Pair<String, String>,
        extra: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = post(path) {
        header(HttpHeaders.Cookie, cookie)
        setBody(
            FormDataContent(
                Parameters.build {
                    csrf?.let { append("_csrf", it) }
                    fields.forEach { (k, v) -> append(k, v) }
                },
            ),
        )
        extra()
    }

    /** Signs in and returns the cookie and the page's anti-forgery token. */
    private suspend fun HttpClient.session(): Pair<String, String> {
        val cookie = signIn().sessionCookieHeader()
        return cookie to csrfOf(page(cookie))
    }

    private suspend fun HttpClient.userLogin(secret: String): HttpResponse = post("/api/auth/login") {
        contentType(ContentType.Application.Json)
        setBody("""{"secret":${jsonString(secret)}}""")
    }

    private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ---- signing in -------------------------------------------------------------------------

    @Test
    fun `the panel asks for the master key first`() = testApplication {
        application { module(AppModule(config())) }
        val response = browser().get("/admin")

        assertEquals(HttpStatusCode.OK, response.status)
        val html = response.bodyAsText()
        assertTrue("Master key" in html && "Sign in" in html)
        assertFalse("Server password" in html, "nothing of the dashboard before signing in")
    }

    @Test
    fun `the panel sends its own security headers`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()

        val panel = client.get("/admin")
        val csp = panel.headers["Content-Security-Policy"].orEmpty()
        assertTrue("form-action 'self'" in csp, "forms must be able to post to the panel: $csp")
        assertTrue("script-src 'self'" in csp && "style-src 'self'" in csp, "still no inline code: $csp")
        assertEquals(1, panel.headers.getAll("Content-Security-Policy")!!.size)
        assertEquals("no-store", panel.headers[HttpHeaders.CacheControl])
        // Browsers send `Origin: null` with form posts under no-referrer, which the server refuses.
        assertEquals("same-origin", panel.headers["Referrer-Policy"])

        val api = client.get("/api/health")
        assertTrue("form-action 'none'" in api.headers["Content-Security-Policy"].orEmpty(), "the rest of the server stays strict")
        assertEquals("no-referrer", api.headers["Referrer-Policy"])
    }

    @Test
    fun `a wrong master key is refused`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()

        for (wrong in listOf("nope", "", SERVER_PASSWORD)) {
            val response = client.signIn(wrong)
            assertEquals(HttpStatusCode.Unauthorized, response.status, "'$wrong'")
            assertTrue(response.cookie() == null, "no session for a wrong key")
        }
    }

    @Test
    fun `the right master key signs in with a locked-down cookie`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()

        val response = client.signIn()
        assertEquals(HttpStatusCode.SeeOther, response.status)
        val cookie = assertNotNull(response.cookie())
        assertTrue("HttpOnly" in cookie, cookie)
        assertTrue("SameSite=Strict" in cookie, cookie)
        assertTrue("Path=/admin" in cookie, cookie)

        val html = client.page(response.sessionCookieHeader())
        assertTrue("Server password" in html && "One-time codes" in html)
    }

    @Test
    fun `failed sign-ins are rate limited`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()

        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, client.signIn("guess$it").status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.signIn("guess-6").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.signIn(MASTER).status, "even the right key waits")
    }

    @Test
    fun `signing out ends the session`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()

        val out = client.form("/admin/logout", cookie, csrf)
        assertEquals(HttpStatusCode.SeeOther, out.status)
        assertTrue("Sign in" in client.page(cookie), "the old cookie no longer opens the dashboard")
    }

    @Test
    fun `a disabled panel is not there at all`() = testApplication {
        application { module(AppModule(config(admin = false))) }
        assertEquals(HttpStatusCode.NotFound, browser().get("/admin").status)
        assertEquals(HttpStatusCode.NotFound, browser().signIn().status)
    }

    @Test
    fun `its stylesheet and scripts are served`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val css = client.get("/admin/assets/admin.css")
        assertEquals(HttpStatusCode.OK, css.status)
        assertTrue(css.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/css"))
        assertEquals(HttpStatusCode.OK, client.get("/admin/assets/admin.js").status)
        assertEquals(HttpStatusCode.OK, client.get("/admin/assets/theme-init.js").status)
        for (sneaky in listOf("/admin/assets/../../../auth.json", "/admin/assets/%2e%2e/%2e%2e/auth.json", "/admin/assets/..%2fauth.json")) {
            val response = client.get(sneaky)
            val body = response.bodyAsText()
            assertTrue(response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.BadRequest, "$sneaky answered ${response.status}: $body")
            assertFalse("hash" in body || "salt" in body, "$sneaky leaked something: $body")
        }
    }

    // ---- forgery ----------------------------------------------------------------------------

    @Test
    fun `a form without the anti-forgery token changes nothing`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, _) = client.session()

        for (token in listOf(null, "", "forged")) {
            val response = client.form("/admin/password", cookie, token, "newPassword" to "Attackers-pass-1", "confirm" to "Attackers-pass-1")
            assertEquals(HttpStatusCode.Forbidden, response.status, "token: $token")
        }
        assertEquals(HttpStatusCode.OK, client.userLogin(SERVER_PASSWORD).status, "the password is untouched")
    }

    @Test
    fun `a post from another site is refused even with a valid token`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()

        val crossSite = client.form("/admin/sessions/revoke-all", cookie, csrf) { header("Sec-Fetch-Site", "cross-site") }
        assertEquals(HttpStatusCode.Forbidden, crossSite.status)
        val foreignOrigin = client.form("/admin/sessions/revoke-all", cookie, csrf) {
            header(HttpHeaders.Origin, "https://evil.example")
            header(HttpHeaders.Host, "localhost")
        }
        assertEquals(HttpStatusCode.Forbidden, foreignOrigin.status)
        val sameOrigin = client.form("/admin/sessions/revoke-all", cookie, csrf) { header("Sec-Fetch-Site", "same-origin") }
        assertEquals(HttpStatusCode.SeeOther, sameOrigin.status)
    }

    @Test
    fun `without a session every action just sends you to sign in`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()

        val response = client.post("/admin/codes") { setBody(FormDataContent(Parameters.build { append("_csrf", "x") })) }
        assertEquals(HttpStatusCode.SeeOther, response.status)
        assertEquals("/admin", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `the admin cookie is not a way into the chat server`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val cookie = client.signIn().sessionCookieHeader()
        val token = cookie.substringAfter('=')

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/chat-link") { header(ACCESS_KEY_HEADER, token) }.status)
    }

    // ---- the server password ------------------------------------------------------------------

    @Test
    fun `changing the password takes effect at once`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()

        val response = client.form("/admin/password", cookie, csrf, "newPassword" to "Fresh-password-77", "confirm" to "Fresh-password-77")
        assertEquals(HttpStatusCode.SeeOther, response.status)
        assertTrue("Password changed" in client.page(cookie))

        assertEquals(HttpStatusCode.Unauthorized, client.userLogin(SERVER_PASSWORD).status)
        assertEquals(HttpStatusCode.OK, client.userLogin("Fresh-password-77").status)
    }

    @Test
    fun `a mismatched or weak password is refused with a message`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()

        client.form("/admin/password", cookie, csrf, "newPassword" to "Fresh-password-77", "confirm" to "Different-password")
        assertTrue("do not match" in client.page(cookie))

        client.form("/admin/password", cookie, csrf, "newPassword" to "short", "confirm" to "short")
        assertTrue("at least 8" in client.page(cookie))

        assertEquals(HttpStatusCode.OK, client.userLogin(SERVER_PASSWORD).status, "still the old one")
    }

    @Test
    fun `changing the password can sign everybody out`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()
        val token = jsonField(client.userLogin(SERVER_PASSWORD).bodyAsText(), "token")
        assertEquals(HttpStatusCode.OK, client.post("/api/chat-link") { header(ACCESS_KEY_HEADER, token) }.status)

        client.form("/admin/password", cookie, csrf, "newPassword" to "Fresh-password-77", "confirm" to "Fresh-password-77", "signOut" to "on")
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/chat-link") { header(ACCESS_KEY_HEADER, token) }.status)
    }

    // ---- the password tile ------------------------------------------------------------------------

    private suspend fun HttpClient.reveal(cookie: String?, csrf: String?, extra: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        post("/admin/password/reveal") {
            cookie?.let { header(HttpHeaders.Cookie, it) }
            setBody(FormDataContent(Parameters.build { csrf?.let { append("_csrf", it) } }))
            extra()
        }

    @Test
    fun `the password tile shows it masked and keeps it out of the page`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val html = client.page(client.signIn().sessionCookieHeader())

        assertTrue("Server password" in html)
        assertTrue("********" in html, "masked until asked for")
        assertFalse(SERVER_PASSWORD in html, "the real password is never in the page source")
        assertTrue("data-reveal-url=\"/admin/password/reveal\"" in html)
        assertFalse("stored hashed" in html || "set here" in html, "no note about where it comes from")
        assertFalse("Admin panel" in html.substringAfter("Server password").substringBefore("Signed in"))
    }

    @Test
    fun `asking to show the password returns it, uncached`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()

        val response = client.reveal(cookie, csrf)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(SERVER_PASSWORD, response.bodyAsText())
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/plain"))
    }

    @Test
    fun `showing the password needs a session, the token, and a same-site request`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()

        assertEquals(HttpStatusCode.Unauthorized, client.reveal(null, csrf).status, "no session")
        assertEquals(HttpStatusCode.Forbidden, client.reveal(cookie, null).status, "no token")
        assertEquals(HttpStatusCode.Forbidden, client.reveal(cookie, "forged").status, "wrong token")
        assertEquals(HttpStatusCode.Forbidden, client.reveal(cookie, csrf) { header("Sec-Fetch-Site", "cross-site") }.status)
        assertEquals(HttpStatusCode.OK, client.reveal(cookie, csrf) { header("Sec-Fetch-Site", "same-origin") }.status)
    }

    @Test
    fun `a password changed in the panel is what the tile shows next`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()

        client.form("/admin/password", cookie, csrf, "newPassword" to "Brand-new-secret-9", "confirm" to "Brand-new-secret-9")
        assertEquals("Brand-new-secret-9", client.reveal(cookie, csrf).bodyAsText())
        assertFalse("Brand-new-secret-9" in client.page(cookie), "still not in the page")
    }

    @Test
    fun `with no password there is nothing to show`() = testApplication {
        application { module(AppModule(ServerConfig(accessKeys = emptyList(), masterKey = MASTER))) }
        val client = browser()
        val (cookie, csrf) = client.session()

        assertEquals(HttpStatusCode.NotFound, client.reveal(cookie, csrf).status)
        val html = client.page(cookie)
        assertTrue("Not set" in html)
        assertFalse("secret-toggle" in html)
    }

    // ---- code lifetimes -----------------------------------------------------------------------------

    @Test
    fun `lifetimes go up to a year`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val html = client.page(client.signIn().sessionCookieHeader())

        for (label in listOf("5 minutes", "15 minutes", "1 hour", "6 hours", "24 hours", "1 week", "1 month", "3 months", "1 year")) {
            assertTrue(">$label<" in html, "missing $label")
        }
        for (seconds in listOf(604_800, 2_592_000, 7_776_000, 31_536_000)) {
            assertTrue("value=\"$seconds\"" in html, "missing $seconds")
        }
    }

    @Test
    fun `one month is preselected as the lifetime`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val html = client.page(client.signIn().sessionCookieHeader())

        val selected = Regex("<option[^>]*selected[^>]*>([^<]*)</option>").findAll(html.substringAfter("Valid for")).map { it.groupValues[1] }.toList()
        assertEquals(listOf("1 month"), selected)
    }

    @Test
    fun `a code made without choosing a lifetime lasts a month`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()

        client.form("/admin/codes", cookie, csrf, "code" to "No-Lifetime-Given-1", "label" to "default")
        val row = client.page(cookie).substringAfter("default").take(400)
        assertTrue(Regex("(29d 2[0-3]h|30d 0h)").containsMatchIn(row), row)
    }

    @Test
    fun `a code made for a year or a month works and is listed with days left`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()

        client.form("/admin/codes", cookie, csrf, "code" to "Year-Long-Code-1", "ttl" to "31536000", "label" to "yearly")
        client.form("/admin/codes", cookie, csrf, "code" to "Month-Long-Code-1", "ttl" to "2592000", "label" to "monthly")
        val html = client.page(cookie)
        assertTrue(Regex("364d 2[0-3]h|365d 0h").containsMatchIn(html), "a year shows as days: " + html.substringAfter("yearly").take(300))
        assertTrue("29d" in html || "30d" in html)
        assertEquals(HttpStatusCode.OK, client.userLogin("Year-Long-Code-1").status)
    }

    @Test
    fun `a lifetime past a year is refused`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()

        client.form("/admin/codes", cookie, csrf, "ttl" to "31536001")
        assertTrue("between a minute and a year" in client.page(cookie))
    }

    @Test
    fun `remaining time reads well from seconds to months`() {
        assertEquals("expired", com.eeck.server.features.auth.admin.formatRemaining(0))
        assertEquals("0:59", com.eeck.server.features.auth.admin.formatRemaining(59))
        assertEquals("14:05", com.eeck.server.features.auth.admin.formatRemaining(14 * 60 + 5))
        assertEquals("1:00:00", com.eeck.server.features.auth.admin.formatRemaining(3600))
        assertEquals("23:59:59", com.eeck.server.features.auth.admin.formatRemaining(86_399))
        assertEquals("1d 0h", com.eeck.server.features.auth.admin.formatRemaining(86_400))
        assertEquals("7d 0h", com.eeck.server.features.auth.admin.formatRemaining(7 * 86_400L))
        assertEquals("365d 0h", com.eeck.server.features.auth.admin.formatRemaining(365 * 86_400L))
    }

    // ---- who is let in --------------------------------------------------------------------------

    private suspend fun HttpClient.health(): String = get("/api/health").bodyAsText()

    @Test
    fun `the access tile is a dropdown showing the current choice`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val html = client.page(client.signIn().sessionCookieHeader())

        assertTrue(Regex("<select[^>]*name=\"mode\"").containsMatchIn(html), "a select named mode")
        val protectedOption = Regex("<option[^>]*value=\"protected\"[^>]*>").find(html)!!.value
        assertTrue("selected" in protectedOption, protectedOption)
        assertTrue("All accepted" in html && "Protected" in html)
        assertTrue("password or code required" in html)
    }

    @Test
    fun `choosing all accepted opens the server at once, and protected closes it again`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/chat-link").status)
        assertTrue("\"keyRequired\":true" in client.health())

        val opened = client.form("/admin/access", cookie, csrf, "mode" to "open")
        assertEquals(HttpStatusCode.SeeOther, opened.status)
        assertTrue("Everyone is accepted" in client.page(cookie))
        assertEquals(HttpStatusCode.OK, client.post("/api/chat-link").status, "no password or code needed now")
        assertTrue("\"keyRequired\":false" in client.health())
        val html = client.page(cookie)
        assertTrue("anyone can join" in html)
        assertTrue("This server accepts everyone" in html)

        client.form("/admin/access", cookie, csrf, "mode" to "protected")
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/chat-link").status)
        assertTrue("\"keyRequired\":true" in client.health())
        assertEquals(HttpStatusCode.OK, client.userLogin(SERVER_PASSWORD).status, "the password still works")
    }

    @Test
    fun `an unknown access value changes nothing`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()

        client.form("/admin/access", cookie, csrf, "mode" to "wide-open")
        assertTrue("Choose Protected or All accepted" in client.page(cookie))
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/chat-link").status)
    }

    @Test
    fun `the access switch needs the anti-forgery token and a same-site request`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()

        assertEquals(HttpStatusCode.Forbidden, client.form("/admin/access", cookie, null, "mode" to "open").status)
        assertEquals(HttpStatusCode.Forbidden, client.form("/admin/access", cookie, "forged", "mode" to "open").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.form("/admin/access", cookie, csrf, "mode" to "open") { header("Sec-Fetch-Site", "cross-site") }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/chat-link").status, "still protected")
    }

    @Test
    fun `protected without a password says that only codes get in`() = testApplication {
        application { module(AppModule(ServerConfig(accessKeys = emptyList(), masterKey = MASTER))) }
        val client = browser()
        val (cookie, csrf) = client.session()

        client.form("/admin/access", cookie, csrf, "mode" to "protected")
        assertTrue("one-time codes only" in client.page(cookie))

        client.form("/admin/codes", cookie, csrf, "code" to "Invite-Only-2026", "ttl" to "900")
        assertEquals(HttpStatusCode.OK, client.userLogin("Invite-Only-2026").status)
        assertEquals(HttpStatusCode.Unauthorized, client.userLogin("any-password").status)
    }

    // ---- one-time codes -----------------------------------------------------------------------

    private fun jsonField(json: String, name: String): String =
        Regex(""""$name"\s*:\s*"([^"]*)"""").find(json)!!.groupValues[1]

    private fun shownCode(html: String): String =
        Regex("""id="new-code">([^<]+)<""").find(html)!!.groupValues[1]

    @Test
    fun `a generated code is shown once and works once`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()

        client.form("/admin/codes", cookie, csrf, "ttl" to "900", "label" to "for Vasya")
        val html = client.page(cookie)
        val code = shownCode(html)
        assertTrue("for Vasya" in client.page(cookie), "listed afterwards")
        assertFalse("new-code" in client.page(cookie), "the code is not shown a second time")
        assertFalse(code in client.page(cookie))

        val login = client.userLogin(code)
        assertEquals(HttpStatusCode.OK, login.status)
        assertEquals("one-time-code", jsonField(login.bodyAsText(), "method"))
        assertEquals(HttpStatusCode.Unauthorized, client.userLogin(code).status, "spent")
        assertTrue("for Vasya" !in client.page(cookie), "a spent code leaves the list")
    }

    @Test
    fun `a code the admin typed works once and is refused if weak`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()

        client.form("/admin/codes", cookie, csrf, "code" to "weak", "ttl" to "900")
        assertTrue("at least 8" in client.page(cookie))

        client.form("/admin/codes", cookie, csrf, "code" to "My-Own-Code-42", "ttl" to "3600")
        assertTrue("Code created" in client.page(cookie))
        assertEquals(HttpStatusCode.OK, client.userLogin("My-Own-Code-42").status)
        assertEquals(HttpStatusCode.Unauthorized, client.userLogin("My-Own-Code-42").status)
    }

    @Test
    fun `a revoked code no longer works`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()

        client.form("/admin/codes", cookie, csrf, "code" to "Revoke-Me-9876", "ttl" to "900")
        val id = Regex("""/admin/codes/([^/]+)/revoke""").find(client.page(cookie))!!.groupValues[1]
        client.form("/admin/codes/$id/revoke", cookie, csrf)
        assertEquals(HttpStatusCode.Unauthorized, client.userLogin("Revoke-Me-9876").status)
        assertTrue("Code revoked" in client.page(cookie))
    }

    @Test
    fun `a ridiculous lifetime is refused`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()

        client.form("/admin/codes", cookie, csrf, "ttl" to "99999999")
        assertTrue("between a minute and a year" in client.page(cookie))
    }

    @Test
    fun `what the admin types is escaped, never run`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()

        client.form("/admin/codes", cookie, csrf, "code" to "Escape-Test-1234", "ttl" to "900", "label" to "<script>alert(1)</script>")
        val html = client.page(cookie)
        assertFalse("<script>alert(1)" in html)
        assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in html)
    }

    @Test
    fun `signing everyone out ends user sessions`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()
        val (cookie, csrf) = client.session()
        val token = jsonField(client.userLogin(SERVER_PASSWORD).bodyAsText(), "token")

        client.form("/admin/sessions/revoke-all", cookie, csrf)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/chat-link") { header(ACCESS_KEY_HEADER, token) }.status)
        assertTrue("session(s) ended" in client.page(cookie))
    }

    // ---- signing in as a user -----------------------------------------------------------------

    @Test
    fun `the token from signing in opens the api, the password is not needed again`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()

        val token = jsonField(client.userLogin(SERVER_PASSWORD).bodyAsText(), "token")
        assertEquals(HttpStatusCode.OK, client.post("/api/chat-link") { header(ACCESS_KEY_HEADER, token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/chat-link") { header(ACCESS_KEY_HEADER, "not-$token") }.status)
    }

    @Test
    fun `a wrong password or code gets one generic answer`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()

        val wrongPassword = client.userLogin("nope")
        val unknownCode = client.userLogin("ABCDE-FGHJK")
        assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)
        assertEquals(wrongPassword.bodyAsText(), unknownCode.bodyAsText(), "no hint which of the two it was")
    }

    @Test
    fun `guessing at the login is limited and shares the gate's budget`() = testApplication {
        application { module(AppModule(config())) }
        val client = browser()

        repeat(5) { client.userLogin("guess$it") }
        repeat(5) { client.post("/api/chat-link") { header(ACCESS_KEY_HEADER, "guess$it") } }
        assertEquals(HttpStatusCode.TooManyRequests, client.userLogin(SERVER_PASSWORD).status)
    }

    @Test
    fun `an open server has nothing to sign in to`() = testApplication {
        application { module(AppModule(ServerConfig(accessKeys = emptyList(), masterKey = MASTER))) }
        assertEquals(HttpStatusCode.BadRequest, browser().userLogin("anything").status)
        assertTrue("This server accepts everyone" in browser().let { c ->
            val cookie = c.signIn().sessionCookieHeader()
            c.page(cookie)
        })
    }
}
