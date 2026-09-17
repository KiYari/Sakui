package com.eeck.server.features.chat.dto

import io.ktor.websocket.CloseReason

/**
 * Custom close codes in RFC 6455's private-use range (4000-4999). Part of the
 * wire protocol: the client treats every one of them except
 * [TOO_MANY_CONNECTIONS] as terminal and never auto-reconnects after it.
 */
object ChatCloseReasons {
    val BAD_REQUEST = CloseReason(4000, "bad-request")
    val ROOM_FULL = CloseReason(4001, "room-full")
    val INVALID_SESSION = CloseReason(4002, "invalid-session")
    val HANDSHAKE_FAILED = CloseReason(4003, "handshake-failed")

    /**
     * Another connection proved ownership of the same key. Terminal on purpose:
     * if the old socket reconnected, two tabs holding one identity would evict
     * each other forever.
     */
    val REPLACED = CloseReason(4004, "replaced")
    val REJECTED = CloseReason(4005, "rejected")
    val LINK_DELETED = CloseReason(4006, "link-deleted")

    /** Transient: this client address holds too many sockets; retrying later can succeed. */
    val TOO_MANY_CONNECTIONS = CloseReason(4007, "too-many-connections")
}
