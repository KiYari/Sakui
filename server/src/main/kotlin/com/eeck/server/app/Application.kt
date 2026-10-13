package com.eeck.server.app

import com.eeck.server.core.config.DotEnv
import com.eeck.server.core.config.Env
import com.eeck.server.core.config.ServerConfig
import com.eeck.server.core.errors.ErrorResponse
import com.eeck.server.core.http.ACCESS_KEY_HEADER
import com.eeck.server.core.http.accessKeyProtected
import com.eeck.server.core.http.SecurityHeaders
import com.eeck.server.core.http.redactOpaqueTokens
import com.eeck.server.core.http.spaStaticFiles
import com.eeck.server.features.auth.admin.adminRoutes
import com.eeck.server.features.auth.resource.authRoutes
import com.eeck.server.features.chat.resource.chatWebSocket
import com.eeck.server.features.session.resource.CREATE_LINK_RATE_LIMIT
import com.eeck.server.features.session.resource.sessionRoutes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration.Companion.minutes

fun main() {
    // A `.env` file underneath the real environment, so an IDE or `gradlew run` configures the
    // server the same way `docker compose` does. Real variables still win.
    val dotEnv = DotEnv.load()
    Env.lookup = DotEnv.layered(System::getenv, dotEnv.values)
    if (dotEnv.files.isNotEmpty()) println("Configuration read from: ${dotEnv.files.joinToString { it.absolutePath }}")
    // A real server keeps what the admin panel changes; only tests run without a data directory.
    val config = ServerConfig().let { if (it.dataDir == null) it.copy(dataDir = "data") else it }
    val appModule = AppModule(config)
    embeddedServer(Netty, port = appModule.config.port, host = appModule.config.host) { module(appModule) }.start(wait = true)
}

fun Application.module(app: AppModule = AppModule()) {
    install(SecurityHeaders)
    install(ContentNegotiation) {
        json()
    }
    if (app.config.trustProxy) {
        // Must come before anything that reads the client address (rate limits,
        // the WebSocket connection cap). The last hop is the proxy in front of us,
        // which overwrites the header, so an address a client forged is ignored.
        install(XForwardedHeaders) {
            useLastProxy()
        }
    }
    install(CORS) {
        for (host in app.config.corsAllowedHosts) {
            // A page served over https may only talk to https; plain http is for local development.
            val local = host.substringBefore(':') in setOf("localhost", "127.0.0.1")
            allowHost(host, schemes = if (local) listOf("http", "https") else listOf("https"))
        }
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(ACCESS_KEY_HEADER)
        allowMethod(HttpMethod.Delete)
    }
    install(CallLogging) {
        // The default format logs the raw path, which carries chat ids — bearer
        // secrets. Query strings (the WebSocket's `chatId`) are left out entirely.
        format { call ->
            "${call.response.status()}: ${call.request.httpMethod.value} - ${redactOpaqueTokens(call.request.path())}"
        }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.message ?: "Internal server error"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse("Not found"))
        }
        // RateLimit answers with an empty body; clients parse every error as JSON.
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, ErrorResponse("Too many requests. Try again in a minute."))
        }
    }
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(Json)
        pingPeriodMillis = 15_000
        timeoutMillis = 30_000
    }
    install(RateLimit) {
        register(CREATE_LINK_RATE_LIMIT) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            // Without a key every client shares one bucket, so ten requests from
            // anyone would lock everyone else out of creating links.
            requestKey { call -> call.request.origin.remoteHost }
        }
    }

    val status = app.authService.status()
    if (status.accessRequired) {
        log.info("Access: protected. Password source: ${status.passwordSource.name.lowercase().replace('_', ' ')}.")
    } else {
        log.warn("Access: open to everyone. Switch it to Protected in the admin panel, or set a password (EECK_ACCESS_KEY).")
    }
    if (app.config.adminEnabled) {
        log.info("Admin panel at /admin.")
        app.authService.autoGeneratedMasterKey?.let { key ->
            // EECK_MASTER_KEY is not set, so this key exists only for this run: written to the log and
            // nowhere else, and different next time. Put EECK_MASTER_KEY in .env for a fixed one.
            log.warn("Admin master key (EECK_MASTER_KEY is not set, so this one was generated for this run and changes on every restart): $key")
        } ?: log.info("Admin master key: from EECK_MASTER_KEY.")
    } else {
        log.info("Admin panel disabled (EECK_ADMIN_ENABLED=false).")
    }

    val webDistDir = File(app.config.webDistPath)
    var servingWebClient = false
    routing {
        route("/api") {
            get("/health") {
                call.respond(HealthResponse(status = "ok", keyRequired = app.accessKeyGuard.required))
            }
            // How a client trades the password or a one-time code for a session token.
            authRoutes(app.authService, app.accessKeyGuard.limiter)
            // Everything below needs the access key. Installing the gate on the route (not matching
            // paths up front) means no spelling of a URL can reach these handlers without passing it.
            accessKeyProtected(app.accessKeyGuard) {
                // A 200 here is how a client learns its key is right.
                get("/access") {
                    call.respond(HealthResponse(status = "ok", keyRequired = app.accessKeyGuard.required))
                }
                sessionRoutes(app.sessionService)
            }
        }
        // The admin panel is server-rendered here and guarded by its own master key, not the access key.
        if (app.config.adminEnabled) adminRoutes(app.authService)
        // A WebSocket handshake cannot carry headers, so this route also takes the key as `?key=`.
        accessKeyProtected(app.accessKeyGuard, allowQueryKey = true) {
            chatWebSocket(app.chatRoomRegistry, app.sessionService, connectionLimiter = app.chatConnectionLimiter)
        }
        servingWebClient = spaStaticFiles(webDistDir)
    }
    if (servingWebClient) {
        log.info("Serving the web client from ${webDistDir.absolutePath}")
    } else {
        log.info("No web client at '${webDistDir.path}' — serving /api and /ws only.")
    }
}

@Serializable
data class HealthResponse(val status: String, val keyRequired: Boolean = false)
