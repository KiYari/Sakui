package com.eeck.server.chat

import kotlinx.serialization.Serializable

/** Field names verbatim from SPEC.md §1's `LinkType`/`ServerLinkResponse` shape. */
@Serializable
data class LinkResponse(val hash: String, val expired: Boolean, val deleted: Boolean)

/** Success shape for `GET /api/chat-link/status/{channel}`, per SPEC.md §1. */
@Serializable
data class StatusResponse(val status: String, val state: String)

/** Error shape for the same endpoint's 404/410 cases, per SPEC.md §1. */
@Serializable
data class StatusErrorResponse(val error: String, val state: String)

/** Success shape for `DELETE /api/chat-link/{channel}`, per SPEC.md §1. */
@Serializable
data class DeleteResponse(val status: String)
