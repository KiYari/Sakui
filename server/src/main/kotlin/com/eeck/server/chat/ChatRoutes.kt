package com.eeck.server.chat

import com.eeck.server.common.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * `POST /api/chat-link`, `GET /api/chat-link/status/{channel}`,
 * `DELETE /api/chat-link/{channel}` — see SPEC.md §1.
 *
 * Routes only parse/validate input shape and map [ChatService]'s results to
 * HTTP status/bodies; all session-state logic lives in [ChatService].
 */
fun Route.chatLinkRoutes(service: ChatService) {
    route("/chat-link") {
        post {
            val session = service.createLink()
            call.respond(
                HttpStatusCode.OK,
                LinkResponse(hash = session.id, expired = false, deleted = session.deleted),
            )
        }

        get("/status/{channel}") {
            val channel = call.parameters["channel"]!!
            if (!ChatIdGenerator.isValidFormat(channel)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed chat id."))
                return@get
            }
            when (service.status(channel)) {
                LinkStatusResult.Active ->
                    call.respond(HttpStatusCode.OK, StatusResponse(status = "ok", state = "ACTIVE"))
                LinkStatusResult.Deleted ->
                    call.respond(HttpStatusCode.Gone, StatusErrorResponse(error = "Channel deleted", state = "DELETED"))
                LinkStatusResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, StatusErrorResponse(error = "Invalid channel", state = "NOT_FOUND"))
            }
        }

        delete("/{channel}") {
            val channel = call.parameters["channel"]!!
            if (!ChatIdGenerator.isValidFormat(channel)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed chat id."))
                return@delete
            }
            when (service.delete(channel)) {
                DeleteLinkResult.Deleted ->
                    call.respond(HttpStatusCode.OK, DeleteResponse(status = "ok"))
                DeleteLinkResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
            }
        }
    }
}
