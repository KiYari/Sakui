package com.eeck.server.common

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: String)
