package com.eeck.server.core.http

import com.eeck.server.core.errors.ErrorResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.application.install
import java.security.MessageDigest

/** Header carrying the key on REST calls. A WebSocket handshake cannot set headers, so `/ws` takes it as `?key=`. */
const val ACCESS_KEY_HEADER = "X-Eeck-Key"
const val ACCESS_KEY_PARAM = "key"

sealed interface AccessVerdict {
    data object Allowed : AccessVerdict
    data object Missing : AccessVerdict
    data object Wrong : AccessVerdict
    data object Throttled : AccessVerdict
}

/**
 * What the server accepts as proof of access. Declared here, in `core`, so the
 * gate need not know where credentials come from (a fixed list, or the auth
 * feature's passwords, one-time codes and sessions).
 */
interface AccessCredentials {
    /** False means the server is open and nothing is checked. */
    val required: Boolean

    fun accepts(provided: String): Boolean
}

/** A fixed set of accepted keys: what `EECK_ACCESS_KEY` alone gave before accounts existed. */
class StaticAccessKeys(keys: List<String>) : AccessCredentials {
    private val accepted = keys.map(::digest)

    override val required: Boolean get() = accepted.isNotEmpty()

    override fun accepts(provided: String): Boolean {
        val providedDigest = digest(provided)
        var matched = false
        // Every key is compared with no early exit, so which one matched is not observable.
        for (key in accepted) matched = MessageDigest.isEqual(key, providedDigest) || matched
        return matched
    }
}

/**
 * Decides whether a request holds an accepted credential, and slows down clients
 * that keep guessing. One instance serves every protected route, so the failure
 * budget is per client address, not per route.
 *
 * With nothing required, [check] always allows.
 */
class AccessKeyGuard(
    private val credentials: AccessCredentials,
    val limiter: AttemptLimiter = AttemptLimiter(),
) {
    val required: Boolean get() = credentials.required

    fun check(client: String, provided: String?): AccessVerdict {
        if (!required) return AccessVerdict.Allowed
        if (limiter.isThrottled(client)) return AccessVerdict.Throttled
        // An empty value is the same as none: it must never match.
        if (provided.isNullOrEmpty()) return AccessVerdict.Missing
        if (credentials.accepts(provided)) return AccessVerdict.Allowed
        limiter.recordFailure(client)
        return AccessVerdict.Wrong
    }

    companion object {
        fun ofKeys(keys: List<String>, nowMillis: () -> Long = System::currentTimeMillis): AccessKeyGuard =
            AccessKeyGuard(StaticAccessKeys(keys), AttemptLimiter(nowMillis = nowMillis))
    }
}

class AccessKeyGateConfig {
    lateinit var guard: AccessKeyGuard

    /**
     * Accept the key as `?key=` as well as the header. Only the WebSocket route
     * turns this on — a handshake cannot carry headers — because a query string
     * is the likelier of the two to end up in someone's log.
     */
    var allowQueryKey: Boolean = false
}

/**
 * Restricts the routes it is installed on to holders of an access key
 * (`EECK_ACCESS_KEY`). It is installed on the routes themselves rather than
 * matching paths on the way in: a path check can be walked around by a
 * differently spelled path, while a request that reaches a protected handler
 * has necessarily passed through the route that carries this.
 */
val AccessKeyGate = createRouteScopedPlugin("AccessKeyGate", ::AccessKeyGateConfig) {
    val guard = pluginConfig.guard
    val allowQuery = pluginConfig.allowQueryKey

    onCall { call ->
        // Preflights are answered by CORS before routing and carry no key by design.
        if (!guard.required || call.request.httpMethod == HttpMethod.Options) return@onCall
        val provided = call.request.headers[ACCESS_KEY_HEADER]?.let(::decodeHeaderKey)
            ?: if (allowQuery) call.request.queryParameters[ACCESS_KEY_PARAM] else null
        when (guard.check(call.clientKey(), provided)) {
            AccessVerdict.Allowed -> Unit
            AccessVerdict.Missing -> call.refuse(HttpStatusCode.Unauthorized, "This server needs a password or a one-time code.")
            AccessVerdict.Wrong -> call.refuse(HttpStatusCode.Unauthorized, "Your sign-in is no longer valid. Enter the password or a new code.")
            AccessVerdict.Throttled ->
                call.refuse(HttpStatusCode.TooManyRequests, "Too many wrong access keys. Try again in a minute.")
        }
    }
}

/**
 * Routes built in [build] are reachable only with an access key. This makes a
 * real child route (like `authenticate` does) rather than `route("")`, which
 * Ktor collapses into its parent and would drag sibling routes behind the gate.
 */
fun Route.accessKeyProtected(
    guard: AccessKeyGuard,
    allowQueryKey: Boolean = false,
    build: Route.() -> Unit,
): Route {
    val child = createChild(AccessKeyRouteSelector)
    child.install(AccessKeyGate) {
        this.guard = guard
        this.allowQueryKey = allowQueryKey
    }
    child.build()
    return child
}

private object AccessKeyRouteSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(access key)"
}

private fun ApplicationCall.clientKey(): String = request.origin.remoteHost

private suspend fun ApplicationCall.refuse(status: HttpStatusCode, message: String) {
    application.environment.log.info("Refused ${request.httpMethod.value} (${status.value}): $message [${clientKey()}]")
    respond(status, ErrorResponse(message))
}

/**
 * A header value is Latin-1 at best, so the client percent-encodes the key (any
 * character, e.g. Cyrillic, then survives); undo that here. A value that is not
 * valid percent-encoding is taken as it is.
 */
private fun decodeHeaderKey(value: String): String = try {
    value.decodeURLPart()
} catch (_: Exception) {
    value
}

private fun digest(key: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
