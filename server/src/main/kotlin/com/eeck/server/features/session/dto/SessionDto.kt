package com.eeck.server.features.session.dto

import kotlinx.serialization.Serializable

/** Field names from SPEC.md §1 (`LinkType`), plus `ownerToken`: shown only here, required to delete the link. */
@Serializable
data class LinkResponse(val hash: String, val expired: Boolean, val deleted: Boolean, val ownerToken: String)

/** Success shape for `GET /api/chat-link/status/{channel}`, per SPEC.md §1. */
@Serializable
data class StatusResponse(val status: String, val state: String)

/** Error shape for the same endpoint's 404/410 cases, per SPEC.md §1. */
@Serializable
data class StatusErrorResponse(val error: String, val state: String)

/** Success shape for `DELETE /api/chat-link/{channel}`, per SPEC.md §1. */
@Serializable
data class DeleteResponse(val status: String)
