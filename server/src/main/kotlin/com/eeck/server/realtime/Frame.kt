package com.eeck.server.realtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Client -> server frames. `body` is opaque application payload — the server
 * never parses, validates, or logs it, only relays it (see [ChatWebSocketRoute]).
 */
@Serializable
sealed interface InboundFrame

@Serializable
@SerialName("message")
data class MessageIn(val body: JsonElement) : InboundFrame

/** Server -> client frames — a separate hierarchy from [InboundFrame]. */
@Serializable
sealed interface OutboundFrame

@Serializable
@SerialName("message")
data class MessageOut(val sender: String, val body: JsonElement) : OutboundFrame

@Serializable
@SerialName("participant-joined")
data class ParticipantJoined(val userId: String) : OutboundFrame

@Serializable
@SerialName("participant-left")
data class ParticipantLeft(val userId: String) : OutboundFrame

@Serializable
@SerialName("error")
data class ErrorFrame(val code: String, val message: String) : OutboundFrame
