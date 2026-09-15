package com.eeck.server.core.config

/** Env-overridable so a deployment can change it without a rebuild; defaults match today's hardcoded values. */
data class ServerConfig(
    val port: Int = System.getenv("EECK_PORT")?.toIntOrNull() ?: 3001,
    val corsAllowedHost: String = System.getenv("EECK_CORS_HOST") ?: "localhost:5173",
)
