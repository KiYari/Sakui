package com.eeck.server.core.config

/** Env-overridable so a deployment can change it without a rebuild; defaults suit local development. */
data class ServerConfig(
    val port: Int = Env["EECK_PORT"]?.toIntOrNull() ?: 3001,
    /**
     * Interface to bind. `0.0.0.0` (the default) is fine when nothing else
     * listens on [port] — Docker's own network isolation, or a direct local
     * run. Set to `127.0.0.1` when a TLS-terminating reverse proxy (Caddy,
     * nginx) sits in front on the same host: it keeps this process reachable
     * only through the proxy, so a client can't bypass it and hit plain HTTP
     * directly on [port].
     */
    val host: String = Env["EECK_HOST"] ?: "0.0.0.0",
    /**
     * Web clients hosted on another origin (e.g. a Vercel deployment) that may call
     * this server directly, as `host[:port]`, comma separated (`EECK_CORS_HOST`).
     * Same-origin deployments never trigger CORS, so this only matters for a
     * client hosted elsewhere in `direct` mode.
     */
    val corsAllowedHosts: List<String> = parseHostList(Env["EECK_CORS_HOST"] ?: "localhost:5173"),
    /**
     * Shared secrets (`EECK_ACCESS_KEY`, comma separated so one can be rotated
     * without downtime). When any is set, clients must present one to use the
     * server at all; empty (the default) leaves the server open.
     */
    val accessKeys: List<String> = parseHostList(Env["EECK_ACCESS_KEY"] ?: ""),
    /**
     * Opens the admin panel at `/admin` (`EECK_MASTER_KEY`). Only this key signs in
     * there; it grants no chat access. Unset, a random one is generated on first
     * start and printed once in the log.
     */
    val masterKey: String? = Env["EECK_MASTER_KEY"]?.takeIf { it.isNotBlank() },
    /** Set to `false` to not serve `/admin` at all. */
    val adminEnabled: Boolean = Env["EECK_ADMIN_ENABLED"]?.lowercase() != "false",
    /**
     * Where the admin panel keeps what it changes at runtime (the server password
     * and the master key's hash), as `auth.json`. In Docker this must be a volume,
     * or a changed password is lost with the container. Null keeps everything in
     * memory only (tests); `main` defaults it to `data` so a real server persists.
     */
    val dataDir: String? = Env["EECK_DATA_DIR"],
    /**
     * Honour `X-Forwarded-For` for the client address. Only safe when every
     * request reaches this process through a proxy that sets the header itself
     * (the compose deployment: the server port is never published). Enabled
     * without such a proxy, any client could claim any address and dodge the
     * per-client limits below.
     */
    val trustProxy: Boolean = Env["EECK_TRUST_PROXY"] == "true",
    /**
     * Where to find the built web client (see `web`'s `npm run build`, or
     * `scripts/build-release.bat`), relative to the working directory unless
     * absolute. When this path is not a directory, the server serves only
     * `/api` and `/ws` and nothing is mounted at `/` — the Docker deployment's
     * working directory never has one, since nginx serves the SPA there instead.
     */
    val webDistPath: String = Env["EECK_WEB_DIST"] ?: "web-dist",
)

/** Splits a comma separated host list, ignoring blanks and stray spaces. */
fun parseHostList(raw: String): List<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
