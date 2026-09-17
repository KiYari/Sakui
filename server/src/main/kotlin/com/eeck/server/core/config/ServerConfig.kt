package com.eeck.server.core.config

/** Env-overridable so a deployment can change it without a rebuild; defaults suit local development. */
data class ServerConfig(
    val port: Int = System.getenv("EECK_PORT")?.toIntOrNull() ?: 3001,
    val corsAllowedHost: String = System.getenv("EECK_CORS_HOST") ?: "localhost:5173",
    /**
     * Honour `X-Forwarded-For` for the client address. Only safe when every
     * request reaches this process through a proxy that sets the header itself
     * (the compose deployment: the server port is never published). Enabled
     * without such a proxy, any client could claim any address and dodge the
     * per-client limits below.
     */
    val trustProxy: Boolean = System.getenv("EECK_TRUST_PROXY") == "true",
)
