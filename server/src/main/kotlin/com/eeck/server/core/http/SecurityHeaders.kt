package com.eeck.server.core.http

import io.ktor.server.application.createApplicationPlugin

private val HEADERS = listOf(
    // Scripts and styles only from this origin (the Vite build emits no inline
    // code). Images may be blob: because received attachments render from
    // object URLs. connect-src 'self' covers the same-origin wss:// relay.
    "Content-Security-Policy" to
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; " +
        "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    // Without this a browser may sniff a blob or asset into a more dangerous
    // type than the one it was served with — the exact thing safeMimeType guards against.
    "X-Content-Type-Options" to "nosniff",
    // The chat id lives in the URL query; with a referrer it would leak to any
    // third-party request a page could ever make.
    "Referrer-Policy" to "no-referrer",
    "X-Frame-Options" to "DENY",
    "Cross-Origin-Opener-Policy" to "same-origin",
    "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), payment=()",
)

/**
 * The same header set `web/security-headers.conf` adds in the Docker/nginx
 * deployment — installed here too so every deployment gets it, including the
 * single-process release (`build-release.bat`/`.sh`) and the Caddy-fronted
 * HTTPS one, neither of which has an nginx layer to add these at all. A
 * nginx- or Caddy-fronted deployment that also sets them ends up with the
 * same value twice, which is redundant but harmless; what matters is that
 * this app is never the only layer and also the one with nothing.
 *
 * Kept in lockstep with `web/security-headers.conf` by hand — there is no
 * shared source between a Kotlin server and an nginx config file, so a
 * change to one is a reminder to check the other.
 */
val SecurityHeaders = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        for ((name, value) in HEADERS) {
            if (!call.response.headers.contains(name)) call.response.headers.append(name, value)
        }
    }
}
