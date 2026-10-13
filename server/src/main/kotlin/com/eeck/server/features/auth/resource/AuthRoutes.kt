package com.eeck.server.features.auth.resource

import com.eeck.server.core.errors.ErrorResponse
import com.eeck.server.core.http.AttemptLimiter
import com.eeck.server.features.auth.service.AuthService
import com.eeck.server.features.auth.service.LoginMethod
import com.eeck.server.features.auth.service.LoginResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val secret: String = "")

@Serializable
data class LoginResponse(val token: String, val method: String, val expiresAtMillis: Long)

/**
 * `POST /api/auth/login` — trade the server password, or a one-time code, for a
 * session token; clients send that token afterwards instead of the secret, which is
 * what lets a one-time code be spent exactly once. Deliberately outside the access
 * gate (it is how one gets in) and sharing its [limiter], so guessing here and
 * guessing at the gate draw on one budget per address.
 *
 * A wrong password and a spent, expired or never-issued code are the same answer:
 * telling them apart would tell a guesser which codes have existed.
 */
fun Route.authRoutes(auth: AuthService, limiter: AttemptLimiter) {
    post("/auth/login") {
        val client = call.request.origin.remoteHost
        if (limiter.isThrottled(client)) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Too many wrong attempts. Try again in a minute."))
            return@post
        }
        if (!auth.required) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("This server does not need a password."))
            return@post
        }
        val request = runCatching { call.receive<LoginRequest>() }.getOrDefault(LoginRequest())
        when (val result = auth.login(request.secret)) {
            is LoginResult.Success -> call.respond(
                LoginResponse(
                    token = result.token,
                    method = if (result.method == LoginMethod.ONE_TIME_CODE) "one-time-code" else "password",
                    expiresAtMillis = result.expiresAtMillis,
                ),
            )
            LoginResult.Rejected -> {
                limiter.recordFailure(client)
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("That password or code was not accepted."))
            }
        }
    }

    post("/auth/logout") {
        call.request.headers[com.eeck.server.core.http.ACCESS_KEY_HEADER]?.let(auth::logout)
        call.respond(HttpStatusCode.NoContent)
    }
}
