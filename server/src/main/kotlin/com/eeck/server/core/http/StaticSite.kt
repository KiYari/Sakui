package com.eeck.server.core.http

import io.ktor.server.http.content.staticFiles
import io.ktor.server.routing.Route
import java.io.File

/**
 * Serves the built SPA from [webDistDir] when present — the "single process,
 * no nginx" deployment path (see `scripts/build-release.bat` and the
 * generated `run.bat`). Mounted at the routing root as a catch-all, so `/api`
 * and `/ws` (registered as literal segments elsewhere) still take priority:
 * Ktor's routing always prefers a literal match over a wildcard one,
 * regardless of registration order.
 *
 * `default("index.html")` mirrors nginx.conf's `try_files ... /index.html` —
 * this app keeps its state in a `?chatId=` query parameter rather than the
 * path, so every real path is `/`, but a hard refresh must still work.
 *
 * Absent in the Docker deployment, where nginx serves the SPA instead and
 * this directory never exists in that container — so this silently mounts
 * nothing there. Returns whether it mounted anything, purely so the caller
 * can log which mode the process is running in.
 */
fun Route.spaStaticFiles(webDistDir: File): Boolean {
    if (!webDistDir.isDirectory) return false
    staticFiles("/", webDistDir) {
        default("index.html")
    }
    return true
}
