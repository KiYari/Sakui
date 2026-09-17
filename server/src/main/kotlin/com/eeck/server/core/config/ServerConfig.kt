package com.eeck.server.core.config

/** Env-overridable so a deployment can change it without a rebuild; defaults suit local development. */
data class ServerConfig(
    val port: Int = System.getenv("EECK_PORT")?.toIntOrNull() ?: 3001,
    /**
     * Interface to bind. `0.0.0.0` (the default) is fine when nothing else
     * listens on [port] — Docker's own network isolation, or a direct local
     * run. Set to `127.0.0.1` when a TLS-terminating reverse proxy (Caddy,
     * nginx) sits in front on the same host: it keeps this process reachable
     * only through the proxy, so a client can't bypass it and hit plain HTTP
     * directly on [port].
     */
    val host: String = System.getenv("EECK_HOST") ?: "0.0.0.0",
    val corsAllowedHost: String = System.getenv("EECK_CORS_HOST") ?: "localhost:5173",
    /**
     * Honour `X-Forwarded-For` for the client address. Only safe when every
     * request reaches this process through a proxy that sets the header itself
     * (the compose deployment: the server port is never published). Enabled
     * without such a proxy, any client could claim any address and dodge the
     * per-client limits below.
     */
    val trustProxy: Boolean = System.getenv("EECK_TRUST_PROXY") == "true",
    /**
     * Where to find the built web client (see `web`'s `npm run build`, or
     * `scripts/build-release.bat`), relative to the working directory unless
     * absolute. When this path is not a directory, the server serves only
     * `/api` and `/ws` and nothing is mounted at `/` — the Docker deployment's
     * working directory never has one, since nginx serves the SPA there instead.
     */
    val webDistPath: String = System.getenv("EECK_WEB_DIST") ?: "web-dist",
)
