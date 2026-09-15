package com.eeck.server.realtime

import io.ktor.websocket.CloseReason

/** Custom close codes in RFC 6455's private-use range (4000-4999). */
object ChatCloseReasons {
    val BAD_REQUEST = CloseReason(4000, "bad-request")
    val ROOM_FULL = CloseReason(4001, "room-full")
}
