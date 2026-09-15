package com.eeck.server.realtime

import io.ktor.server.routing.Route
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close
import kotlinx.coroutines.channels.ClosedReceiveChannelException

/**
 * `ws://.../ws?chatId=...&userId=...` — see SPEC.md §2, reimplemented over
 * plain WebSocket. Connecting *is* joining (no separate join frame); message
 * bodies are relayed to the other participant untouched (see [MessageIn]).
 */
fun Route.chatWebSocket(registry: ChatRoomRegistry) {
    webSocket("/ws") {
        val chatId = call.request.queryParameters["chatId"]
        val userId = call.request.queryParameters["userId"]

        if (chatId.isNullOrBlank() || userId.isNullOrBlank()) {
            sendSerialized<OutboundFrame>(ErrorFrame("BAD_REQUEST", "chatId and userId query parameters are required."))
            close(ChatCloseReasons.BAD_REQUEST)
            return@webSocket
        }

        val me = Participant(userId, this)

        val outcome = registry.join(chatId, me)
        if (outcome is JoinOutcome.RoomFull) {
            sendSerialized<OutboundFrame>(ErrorFrame("ROOM_FULL", "This chat already has two participants."))
            close(ChatCloseReasons.ROOM_FULL)
            return@webSocket
        }
        val existingPeer = (outcome as JoinOutcome.Joined).existingPeer
        existingPeer?.sendQuietly(ParticipantJoined(userId))

        try {
            while (true) {
                when (val inbound = receiveDeserialized<InboundFrame>()) {
                    is MessageIn -> {
                        registry.peerFor(chatId, me)?.sendQuietly(MessageOut(sender = userId, body = inbound.body))
                    }
                }
            }
        } catch (_: ClosedReceiveChannelException) {
            // Normal disconnect (client close frame, or a ping/pong timeout) - not an error.
        } finally {
            val remainingPeer = registry.leave(chatId, me)
            remainingPeer?.sendQuietly(ParticipantLeft(userId))
        }
    }
}

/**
 * A peer can disconnect between us looking it up and actually sending to it
 * (join/leave races against relay). That's expected, not a failure of *our*
 * connection, so a send failure here is swallowed rather than propagated.
 */
private suspend fun Participant.sendQuietly(frame: OutboundFrame) {
    runCatching { session.sendSerialized<OutboundFrame>(frame) }
}
