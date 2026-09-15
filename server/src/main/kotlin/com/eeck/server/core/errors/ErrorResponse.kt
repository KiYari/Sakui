package com.eeck.server.core.errors

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: String)
