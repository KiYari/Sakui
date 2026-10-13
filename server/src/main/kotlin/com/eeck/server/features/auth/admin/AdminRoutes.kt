package com.eeck.server.features.auth.admin

import com.eeck.server.core.http.AttemptLimiter
import com.eeck.server.features.auth.model.AccessMode
import com.eeck.server.features.auth.service.AuthService
import com.eeck.server.features.auth.service.ChangePasswordResult
import com.eeck.server.features.auth.service.CreateCodeResult
import com.eeck.server.features.auth.service.Flash
import com.eeck.server.features.auth.service.Session
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.resolveResource
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.http.Parameters
import java.security.MessageDigest

private const val COOKIE = "eeck_admin"
private const val ADMIN_PATH = "/admin"

/**
 * The admin panel: server-rendered pages under `/admin`, served by the chat server
 * itself so there is nothing to host separately and the panel manages exactly the
 * server it runs on.
 *
 * Sign-in is the master key; the session lives in an `HttpOnly`, `SameSite=Strict`
 * cookie scoped to `/admin`, so no script can read it and no other site can make the
 * browser send it. Every form carries a per-session anti-forgery token, and a post is
 * also refused when the browser says it came from another site. Failed sign-ins are
 * limited per address and, more loosely, across all addresses.
 */
fun Route.adminRoutes(auth: AuthService, perClient: AttemptLimiter = AttemptLimiter(limit = 5), overall: AttemptLimiter = AttemptLimiter(limit = 50)) {
    route(ADMIN_PATH) {
        // Assets are resolved by hand so a path is judged before it touches the class path: only plain
        // file names inside the folder qualify, whatever the spelling (`..`, encoded dots, backslashes).
        get("/assets/{path...}") {
            val segments = call.parameters.getAll("path").orEmpty()
            val safe = segments.isNotEmpty() && segments.all { it.matches(ASSET_SEGMENT) && it != "." && it != ".." }
            val content = if (safe) call.resolveResource(segments.joinToString("/"), "admin") else null
            if (content == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                call.respond(content)
            }
        }

        get {
            val session = call.adminSession(auth)
            if (session == null) {
                call.noStore()
                call.respondHtml { loginPage(error = null) }
                return@get
            }
            val flash = session.flash.getAndSet(null)
            call.noStore()
            call.respondHtml {
                dashboardPage(
                    DashboardModel(
                        status = auth.status(),
                        codes = auth.codes.active(),
                        flash = flash,
                        csrf = session.csrf,
                        nowMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }

        post("/login") {
            call.noStore()
            if (!call.sameSite()) return@post call.respond(HttpStatusCode.Forbidden)
            val client = call.request.origin.remoteHost
            if (perClient.isThrottled(client) || overall.isThrottled(GLOBAL)) {
                call.respondHtml(HttpStatusCode.TooManyRequests) { tooManyAttemptsPage() }
                return@post
            }
            val key = call.receiveParameters()["masterKey"].orEmpty()
            val signedIn = if (key.isEmpty()) null else auth.adminLogin(key)
            if (signedIn == null) {
                perClient.recordFailure(client)
                overall.recordFailure(GLOBAL)
                call.respondHtml(HttpStatusCode.Unauthorized) { loginPage(error = "That master key was not accepted.") }
                return@post
            }
            call.response.cookies.append(sessionCookie(call, signedIn.first))
            call.redirectToPanel()
        }

        post("/logout") {
            call.withForm(auth) { session, _ ->
                call.request.cookies[COOKIE]?.let(auth::adminLogout)
                call.response.cookies.append(expiredCookie(call))
                call.redirectToPanel()
                session
            }
        }

        post("/password") {
            call.withForm(auth) { session, form ->
                val password = form["newPassword"].orEmpty()
                when {
                    password != form["confirm"].orEmpty() -> session.flash.set(Flash(Flash.Kind.ERROR, "The two passwords do not match."))
                    else -> when (val result = auth.changePassword(password, signOutEveryone = form["signOut"] != null)) {
                        is ChangePasswordResult.Invalid -> session.flash.set(Flash(Flash.Kind.ERROR, result.reason))
                        is ChangePasswordResult.Changed -> session.flash.set(
                            Flash(
                                Flash.Kind.SUCCESS,
                                if (result.signedOut > 0) "Password changed. ${result.signedOut} signed-in session(s) ended."
                                else "Password changed.",
                            ),
                        )
                    }
                }
                call.redirectToPanel()
            }
        }

        // The password is not in the page's HTML: the tile asks for it only when someone clicks "show",
        // and the answer is never cached.
        post("/password/reveal") {
            call.noStore()
            if (!call.sameSite()) return@post call.respond(HttpStatusCode.Forbidden)
            val session = call.adminSession(auth) ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val sent = call.receiveParameters()["_csrf"].orEmpty()
            if (!MessageDigest.isEqual(sent.toByteArray(), session.csrf.toByteArray())) return@post call.respond(HttpStatusCode.Forbidden)
            val password = auth.revealPassword() ?: return@post call.respond(HttpStatusCode.NotFound)
            call.respondText(password, ContentType.Text.Plain)
        }

        post("/access") {
            call.withForm(auth) { session, form ->
                val mode = when (form["mode"]) {
                    "protected" -> AccessMode.PROTECTED
                    "open" -> AccessMode.OPEN
                    else -> null
                }
                if (mode == null) {
                    session.flash.set(Flash(Flash.Kind.ERROR, "Choose Protected or All accepted."))
                } else {
                    auth.setAccessMode(mode)
                    session.flash.set(
                        when {
                            mode == AccessMode.OPEN -> Flash(Flash.Kind.SUCCESS, "Everyone is accepted now: no password or code is asked for.")
                            auth.status().hasPassword -> Flash(Flash.Kind.SUCCESS, "Protected: a password or a one-time code is required.")
                            else -> Flash(Flash.Kind.SUCCESS, "Protected: no password is set, so only one-time codes can get in. Set a password below to allow it too.")
                        },
                    )
                }
                call.redirectToPanel()
            }
        }

        post("/codes") {
            call.withForm(auth) { session, form ->
                val ttl = form["ttl"]?.toLongOrNull() ?: AuthService.DEFAULT_CODE_TTL_SECONDS
                val typed = form["code"]?.trim()
                when (val result = auth.createCode(typed, ttl, form["label"].orEmpty())) {
                    is CreateCodeResult.Invalid -> session.flash.set(Flash(Flash.Kind.ERROR, result.reason))
                    is CreateCodeResult.Created -> session.flash.set(
                        Flash(
                            Flash.Kind.CODE,
                            if (result.info.custom) "Code created." else "Code generated.",
                            code = result.code,
                        ),
                    )
                }
                call.redirectToPanel()
            }
        }

        post("/codes/{id}/revoke") {
            call.withForm(auth) { session, _ ->
                val revoked = auth.codes.revoke(call.parameters["id"].orEmpty())
                session.flash.set(
                    if (revoked) Flash(Flash.Kind.SUCCESS, "Code revoked.") else Flash(Flash.Kind.ERROR, "That code is already gone."),
                )
                call.redirectToPanel()
            }
        }

        post("/sessions/revoke-all") {
            call.withForm(auth) { session, _ ->
                val ended = auth.signOutEveryone()
                session.flash.set(Flash(Flash.Kind.SUCCESS, "$ended session(s) ended."))
                call.redirectToPanel()
            }
        }
    }
}

private const val GLOBAL = "*"

/** A file or folder name inside the assets folder: letters, digits, dot, dash, underscore. */
private val ASSET_SEGMENT = Regex("[A-Za-z0-9._-]+")

/** After a form post: 303 so the browser follows with a GET and a refresh cannot repeat the post. */
private suspend fun ApplicationCall.redirectToPanel() {
    response.headers.append(HttpHeaders.Location, ADMIN_PATH)
    respond(HttpStatusCode.SeeOther)
}

private fun ApplicationCall.adminSession(auth: AuthService): Session? =
    request.cookies[COOKIE]?.let(auth::adminSession)

/** Pages that show secrets or state must never be cached or kept in history by a proxy or the browser. */
private fun ApplicationCall.noStore() {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
}

/**
 * Runs a state-changing form: the caller must hold an admin session, the form must
 * carry that session's anti-forgery token, and the request must not come from
 * another site. Anything else is refused without running [handle].
 */
private suspend fun ApplicationCall.withForm(
    auth: AuthService,
    handle: suspend (Session, Parameters) -> Any?,
) {
    noStore()
    if (!sameSite()) return respond(HttpStatusCode.Forbidden)
    val session = adminSession(auth) ?: return redirectToPanel()
    val form = receiveParameters()
    val sent = form["_csrf"].orEmpty()
    if (!MessageDigest.isEqual(sent.toByteArray(), session.csrf.toByteArray())) return respond(HttpStatusCode.Forbidden)
    handle(session, form)
}

/**
 * Browsers state where a request came from. `Sec-Fetch-Site` is authoritative where
 * sent; older browsers fall back to comparing `Origin` with the host asked for. A
 * request with neither (a command-line client) has no ambient cookie to abuse.
 */
private fun ApplicationCall.sameSite(): Boolean {
    val fetchSite = request.header("Sec-Fetch-Site")
    val origin = request.header(HttpHeaders.Origin)
    val host = request.header(HttpHeaders.Host)
    val ok = when {
        fetchSite != null -> fetchSite == "same-origin" || fetchSite == "none"
        origin == null -> true
        host == null -> false
        else -> origin.substringAfter("://").substringBefore('/').substringBefore(':').equals(host.substringBefore(':'), ignoreCase = true)
    }
    if (!ok) {
        application.environment.log.info(
            "Refused a cross-site admin request (Sec-Fetch-Site=$fetchSite, Origin=$origin, Host=$host) [${request.origin.remoteHost}]",
        )
    }
    return ok
}

private fun sessionCookie(call: ApplicationCall, token: String) = Cookie(
    name = COOKIE,
    value = token,
    path = ADMIN_PATH,
    httpOnly = true,
    // Behind a TLS-terminating proxy this is true only if it forwards the scheme (see trustProxy).
    secure = call.request.origin.scheme == "https",
    extensions = mapOf("SameSite" to "Strict"),
)

private fun expiredCookie(call: ApplicationCall) =
    sessionCookie(call, "").copy(maxAge = 0)
