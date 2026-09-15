package com.eeck.server.features.session

import com.eeck.server.core.errors.DomainError
import com.eeck.server.core.errors.ErrorResponse
import com.eeck.server.core.errors.Outcome
import com.eeck.server.core.errors.httpStatus
import com.eeck.server.core.ids.ChatId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Shared with the [io.ktor.server.plugins.ratelimit.RateLimit] registration in `app/AppModule`. */
val CREATE_LINK_RATE_LIMIT = RateLimitName("create-link")

/**
 * `POST /api/chat-link`, `GET /api/chat-link/status/{channel}`,
 * `DELETE /api/chat-link/{channel}` — see SPEC.md §1.
 *
 * Routes only parse/validate input shape and map [SessionService]'s results to
 * HTTP status/bodies; all session-state logic lives in [SessionService].
 */
fun Route.sessionRoutes(service: SessionService) {
    route("/chat-link") {
        rateLimit(CREATE_LINK_RATE_LIMIT) {
            post {
                val session = service.createLink()
                call.respond(
                    HttpStatusCode.OK,
                    LinkResponse(hash = session.id.value, expired = false, deleted = session.deleted),
                )
            }
        }

        get("/status/{channel}") {
            val channel = call.parameters["channel"]!!
            if (!SessionIdGenerator.isValidFormat(channel)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed chat id."))
                return@get
            }
            when (val outcome = service.status(ChatId(channel))) {
                is Outcome.Success ->
                    call.respond(HttpStatusCode.OK, StatusResponse(status = "ok", state = "ACTIVE"))
                is Outcome.Failure -> {
                    val state = if (outcome.error == DomainError.LinkAlreadyDeleted) "DELETED" else "NOT_FOUND"
                    val message = if (outcome.error == DomainError.LinkAlreadyDeleted) "Channel deleted" else "Invalid channel"
                    call.respond(outcome.error.httpStatus, StatusErrorResponse(error = message, state = state))
                }
            }
        }

        delete("/{channel}") {
            val channel = call.parameters["channel"]!!
            if (!SessionIdGenerator.isValidFormat(channel)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed chat id."))
                return@delete
            }
            when (val outcome = service.delete(ChatId(channel))) {
                is Outcome.Success -> call.respond(HttpStatusCode.OK, DeleteResponse(status = "ok"))
                is Outcome.Failure -> call.respond(outcome.error.httpStatus, ErrorResponse("Not found"))
            }
        }
    }
}
