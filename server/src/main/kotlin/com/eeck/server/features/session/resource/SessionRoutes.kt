package com.eeck.server.features.session.resource

import com.eeck.server.core.errors.DomainError
import com.eeck.server.core.errors.ErrorResponse
import com.eeck.server.core.errors.Outcome
import com.eeck.server.core.errors.httpStatus
import com.eeck.server.core.ids.ChatId
import com.eeck.server.features.session.dto.DeleteResponse
import com.eeck.server.features.session.dto.LinkResponse
import com.eeck.server.features.session.dto.StatusErrorResponse
import com.eeck.server.features.session.dto.StatusResponse
import com.eeck.server.features.session.model.SessionIdGenerator
import com.eeck.server.features.session.service.SessionService
import io.ktor.http.HttpHeaders
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

private const val BEARER_PREFIX = "Bearer "

/**
 * `POST /api/chat-link`, `GET /api/chat-link/status/{channel}`,
 * `DELETE /api/chat-link/{channel}` — see SPEC.md §1.
 *
 * Deleting requires `Authorization: Bearer <ownerToken>` from the create response.
 *
 * Routes only parse/validate input shape and map [SessionService]'s results to
 * HTTP status/bodies; all session-state logic lives in [SessionService].
 */
fun Route.sessionRoutes(service: SessionService) {
    route("/chat-link") {
        // All three endpoints share one per-client budget. GET and DELETE aren't
        // guessing targets — a chat id is 144 bits and an owner token 256 —
        // but an unmetered endpoint is still a bare request-volume target, and
        // POST is the only one of the three actually rate-limited by the
        // WebSocket connection cap or anything else downstream.
        rateLimit(CREATE_LINK_RATE_LIMIT) {
            post {
                val created = service.createLink()
                call.respond(
                    HttpStatusCode.OK,
                    LinkResponse(
                        hash = created.record.id.value,
                        expired = false,
                        deleted = created.record.deleted,
                        ownerToken = created.ownerToken,
                    ),
                )
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
                val token = call.request.headers[HttpHeaders.Authorization]
                    ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
                    ?.substring(BEARER_PREFIX.length)
                    ?.trim()
                when (val outcome = service.delete(ChatId(channel), token)) {
                    is Outcome.Success -> call.respond(HttpStatusCode.OK, DeleteResponse(status = "ok"))
                    is Outcome.Failure -> {
                        val message = if (outcome.error == DomainError.NotLinkOwner) "Only the link's creator can delete it." else "Not found"
                        call.respond(outcome.error.httpStatus, ErrorResponse(message))
                    }
                }
            }
        }
    }
}
