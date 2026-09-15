package com.eeck.server.features.chat

import io.ktor.websocket.CloseReason

/** Custom close codes in RFC 6455's private-use range (4000-4999). */
object ChatCloseReasons {
    val BAD_REQUEST = CloseReason(4000, "bad-request")
    val INVALID_SESSION = CloseReason(4002, "invalid-session")
}
