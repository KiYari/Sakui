package com.eeck.server.features.chat

import com.eeck.server.core.ids.ChatId
import com.eeck.server.core.ids.UserId
import com.eeck.server.core.ws.DEFAULT_MAX_FRAME_SIZE
import io.ktor.server.routing.Route
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close
import kotlinx.coroutines.channels.ClosedReceiveChannelException

/**
 * `ws://.../ws?chatId=...&userId=...` — see SPEC.md §2, reimplemented over
 * plain WebSocket. Connecting *is* joining (no separate join frame); message
 * bodies are relayed to every other participant in the room, untouched (see
 * [MessageIn]). Any number of participants may share a room.
 */
fun Route.chatWebSocket(registry: ChatRoomRegistry, sessionLookup: SessionLookup) {
    webSocket("/ws") {
        maxFrameSize = DEFAULT_MAX_FRAME_SIZE

        val chatIdParam = call.request.queryParameters["chatId"]
        val userIdParam = call.request.queryParameters["userId"]

        if (chatIdParam.isNullOrBlank() || userIdParam.isNullOrBlank()) {
            sendSerialized<OutboundFrame>(ErrorFrame("BAD_REQUEST", "chatId and userId query parameters are required."))
            close(ChatCloseReasons.BAD_REQUEST)
            return@webSocket
        }

        val chatId = ChatId(chatIdParam)
        if (!sessionLookup.isActive(chatId)) {
            sendSerialized<OutboundFrame>(ErrorFrame("INVALID_SESSION", "This chat link does not exist or is no longer active."))
            close(ChatCloseReasons.INVALID_SESSION)
            return@webSocket
        }

        val me = Participant(UserId(userIdParam), this)

        registry.join(chatId, me)

        try {
            while (true) {
                when (val inbound = receiveDeserialized<InboundFrame>()) {
                    is MessageIn -> registry.relay(chatId, me, inbound.body)
                }
            }
        } catch (_: ClosedReceiveChannelException) {
            // Normal disconnect (client close frame, or a ping/pong timeout) - not an error.
        } finally {
            registry.leave(chatId, me)
        }
    }
}
