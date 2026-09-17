package com.eeck.server.features.chat.resource

import com.eeck.server.core.ids.ChatId
import com.eeck.server.core.ids.UserId
import com.eeck.server.core.ws.ConnectionLimiter
import com.eeck.server.core.ws.DEFAULT_MAX_FRAME_SIZE
import com.eeck.server.core.ws.FRAMES_PER_SECOND
import com.eeck.server.core.ws.FRAME_BURST
import com.eeck.server.core.ws.FrameRateLimiter
import com.eeck.server.core.ws.MAX_CONNECTIONS_PER_CLIENT
import com.eeck.server.features.chat.dto.Admit
import com.eeck.server.features.chat.dto.Challenge
import com.eeck.server.features.chat.dto.ChatCloseReasons
import com.eeck.server.features.chat.dto.ErrorFrame
import com.eeck.server.features.chat.dto.Hello
import com.eeck.server.features.chat.dto.InboundFrame
import com.eeck.server.features.chat.dto.MessageIn
import com.eeck.server.features.chat.dto.OutboundFrame
import com.eeck.server.features.chat.dto.Proof
import com.eeck.server.features.chat.dto.Reject
import com.eeck.server.features.chat.dto.Welcome
import com.eeck.server.features.chat.model.Participant
import com.eeck.server.features.chat.port.SessionLookup
import com.eeck.server.features.chat.service.ChatRoomRegistry
import com.eeck.server.features.chat.service.IdentityVerifier
import com.eeck.server.features.chat.service.JoinResult
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/** Long enough for a slow device to unwrap and HMAC; short enough that half-open handshakes can't pile up. */
private val HANDSHAKE_TIMEOUT = 10.seconds

/**
 * `ws://.../ws?chatId=...` — see SPEC.md §2, reimplemented over plain WebSocket.
 *
 * A connection proves which key it holds before it may join: `hello` ->
 * `challenge` -> `proof` -> `welcome` (see [IdentityVerifier]). The identity it
 * joins under is the key fingerprint the server computed, never a value the
 * client asserted. It then waits for the host's admission (see
 * [com.eeck.server.features.chat.service.ChatRoomActor]); message bodies are
 * relayed untouched (see [MessageIn]).
 *
 * [connectionLimiter] is keyed by client address and checked before anything
 * else, so a single client can't hold an unbounded number of sockets open in
 * the (cheap for it, not for us) handshake phase.
 */
fun Route.chatWebSocket(
    registry: ChatRoomRegistry,
    sessionLookup: SessionLookup,
    verifier: IdentityVerifier = IdentityVerifier(),
    connectionLimiter: ConnectionLimiter = ConnectionLimiter(MAX_CONNECTIONS_PER_CLIENT),
) {
    webSocket("/ws") {
        maxFrameSize = DEFAULT_MAX_FRAME_SIZE

        val clientKey = call.request.origin.remoteHost
        if (!connectionLimiter.tryAcquire(clientKey)) {
            reject(ChatCloseReasons.TOO_MANY_CONNECTIONS, "TOO_MANY_CONNECTIONS", "Too many open connections from this address.")
            return@webSocket
        }
        try {
            handleConnection(registry, sessionLookup, verifier)
        } finally {
            connectionLimiter.release(clientKey)
        }
    }
}

private suspend fun DefaultWebSocketServerSession.handleConnection(
    registry: ChatRoomRegistry,
    sessionLookup: SessionLookup,
    verifier: IdentityVerifier,
) {
    val chatIdParam = call.request.queryParameters["chatId"]
    if (chatIdParam.isNullOrBlank()) {
        reject(ChatCloseReasons.BAD_REQUEST, "BAD_REQUEST", "chatId query parameter is required.")
        return
    }

    val chatId = ChatId(chatIdParam)
    if (!sessionLookup.isActive(chatId)) {
        reject(ChatCloseReasons.INVALID_SESSION, "INVALID_SESSION", "This chat link does not exist or is no longer active.")
        return
    }

    val userId = try {
        withTimeout(HANDSHAKE_TIMEOUT) { authenticate(verifier) }
    } catch (_: ClosedReceiveChannelException) {
        return // client gave up mid-handshake; nothing to clean up
    } catch (_: TimeoutCancellationException) {
        null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null // malformed frames are just a failed handshake, not a server error
    }

    if (userId == null) {
        reject(ChatCloseReasons.HANDSHAKE_FAILED, "HANDSHAKE_FAILED", "Could not verify this client's identity key.")
        return
    }

    // Checked again: the link may have been deleted while this client was
    // busy with the handshake, and the room it would join is already gone.
    if (!sessionLookup.isActive(chatId)) {
        reject(ChatCloseReasons.INVALID_SESSION, "INVALID_SESSION", "This chat link does not exist or is no longer active.")
        return
    }

    val me = Participant(userId, this)
    when (registry.join(chatId, me)) {
        JoinResult.JOINED -> Unit
        JoinResult.ROOM_FULL -> {
            reject(ChatCloseReasons.ROOM_FULL, "ROOM_FULL", "This chat is full.")
            return
        }
        JoinResult.ROOM_DELETED, JoinResult.ACTOR_STOPPED -> {
            reject(ChatCloseReasons.LINK_DELETED, "LINK_DELETED", "The creator deleted this chat.")
            return
        }
    }

    val limiter = FrameRateLimiter(FRAME_BURST, FRAMES_PER_SECOND)
    try {
        while (true) {
            val inbound = receiveDeserialized<InboundFrame>()
            // Waiting *before* the next receive is what turns the budget into
            // backpressure: an over-eager sender is slowed through TCP instead
            // of being disconnected halfway through a file.
            val wait = limiter.reserve()
            if (wait.isPositive()) delay(wait)

            when (inbound) {
                is MessageIn -> registry.relay(chatId, me, inbound.body, inbound.to?.let(::UserId))
                // Authority is checked by the room: only the current host's commands take effect.
                is Admit -> registry.admit(chatId, me, UserId(inbound.userId))
                is Reject -> registry.reject(chatId, me, UserId(inbound.userId))
                // The identity is already settled for this connection; a repeated
                // handshake frame cannot change it, so it is simply ignored.
                is Hello, is Proof -> Unit
            }
        }
    } catch (_: ClosedReceiveChannelException) {
        // Normal disconnect (client close frame, a ping/pong timeout, or being replaced).
    } finally {
        registry.leave(chatId, me)
    }
}

/** Runs the challenge-response; null means the client could not prove it holds the key it presented. */
private suspend fun DefaultWebSocketServerSession.authenticate(verifier: IdentityVerifier): UserId? {
    val hello = receiveDeserialized<InboundFrame>() as? Hello ?: return null
    val challenge = verifier.challengeFor(hello.spki) ?: return null
    sendSerialized<OutboundFrame>(Challenge(wrappedKey = challenge.wrappedKey, nonce = challenge.nonce))

    val proof = receiveDeserialized<InboundFrame>() as? Proof ?: return null
    if (!verifier.verify(challenge, proof.mac)) return null

    sendSerialized<OutboundFrame>(Welcome(userId = challenge.userId.value))
    return challenge.userId
}

private suspend fun DefaultWebSocketServerSession.reject(reason: CloseReason, code: String, message: String) {
    sendSerialized<OutboundFrame>(ErrorFrame(code, message))
    close(reason)
}
