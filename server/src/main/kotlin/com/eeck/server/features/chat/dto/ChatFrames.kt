package com.eeck.server.features.chat.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Client -> server frames. `body` is opaque application payload — the server
 * never parses, validates, or logs it, only relays it (see the `resource` and
 * `service` layers).
 *
 * Wire fields stay raw `String` (not [com.eeck.server.core.ids.UserId]/[com.eeck.server.core.ids.ChatId]) —
 * the value classes are an internal domain safety net, not a wire-format concern.
 */
@Serializable
sealed interface InboundFrame

/** Opens the handshake: the client's RSA public key, SPKI DER, standard base64. */
@Serializable
@SerialName("hello")
data class Hello(val spki: String) : InboundFrame

/** HMAC-SHA256 over the handshake context and nonce, keyed with the unwrapped challenge secret. */
@Serializable
@SerialName("proof")
data class Proof(val mac: String) : InboundFrame

/**
 * `to == null` goes to every admitted participant. An addressed frame goes to
 * one participant, and is the only way traffic crosses the admission boundary:
 * the host and a waiting joiner may address each other (to swap keys and
 * profiles before the host decides), nobody else may reach a waiting joiner.
 */
@Serializable
@SerialName("message")
data class MessageIn(val body: JsonElement, val to: String? = null) : InboundFrame

/** Host only: let a waiting joiner into the room. */
@Serializable
@SerialName("admit")
data class Admit(val userId: String) : InboundFrame

/** Host only: turn a waiting joiner away; their connection is closed with [ChatCloseReasons.REJECTED]. */
@Serializable
@SerialName("reject")
data class Reject(val userId: String) : InboundFrame

/** Server -> client frames — a separate hierarchy from [InboundFrame]. */
@Serializable
sealed interface OutboundFrame

/** A secret wrapped to the key from [Hello]; only its private-key holder can answer. */
@Serializable
@SerialName("challenge")
data class Challenge(val wrappedKey: String, val nonce: String) : OutboundFrame

/** Handshake passed; `userId` is the key fingerprint the server will label this connection with. */
@Serializable
@SerialName("welcome")
data class Welcome(val userId: String) : OutboundFrame

@Serializable
enum class AdmissionStatus {
    @SerialName("pending") PENDING,
    @SerialName("admitted") ADMITTED,
}

/** Where this connection stands in the room, and who decides entry. Sent after joining and on admission. */
@Serializable
@SerialName("admission")
data class Admission(val status: AdmissionStatus, val hostId: String) : OutboundFrame

/** The previous host left; entry decisions now belong to [hostId]. Sent to everyone in the room. */
@Serializable
@SerialName("host-changed")
data class HostChanged(val hostId: String) : OutboundFrame

/** To the host only: [userId] is waiting to be admitted. */
@Serializable
@SerialName("join-request")
data class JoinRequest(val userId: String) : OutboundFrame

/** To the host only: [userId] stopped waiting (disconnected before a decision). */
@Serializable
@SerialName("join-request-cancelled")
data class JoinRequestCancelled(val userId: String) : OutboundFrame

@Serializable
@SerialName("message")
data class MessageOut(val sender: String, val body: JsonElement) : OutboundFrame

/**
 * [announce] is true only when [userId] was just admitted — a real arrival worth
 * showing people. It is false when a joiner is told who was already present, or
 * when a participant's connection was replaced by a reconnect.
 */
@Serializable
@SerialName("participant-joined")
data class ParticipantJoined(val userId: String, val announce: Boolean = false) : OutboundFrame

@Serializable
@SerialName("participant-left")
data class ParticipantLeft(val userId: String) : OutboundFrame

@Serializable
@SerialName("error")
data class ErrorFrame(val code: String, val message: String) : OutboundFrame
