package com.eeck.server.app

import com.eeck.server.core.errors.ErrorResponse
import com.eeck.server.core.http.SecurityHeaders
import com.eeck.server.core.http.redactOpaqueTokens
import com.eeck.server.core.http.spaStaticFiles
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
    val appModule = AppModule()
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
        allowHost(app.config.corsAllowedHost)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
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

    val webDistDir = File(app.config.webDistPath)
    var servingWebClient = false
    routing {
        route("/api") {
            get("/health") {
                call.respond(HealthResponse(status = "ok"))
            }
            sessionRoutes(app.sessionService)
        }
        chatWebSocket(app.chatRoomRegistry, app.sessionService, connectionLimiter = app.chatConnectionLimiter)
        servingWebClient = spaStaticFiles(webDistDir)
    }
    if (servingWebClient) {
        log.info("Serving the web client from ${webDistDir.absolutePath}")
    } else {
        log.info("No web client at '${webDistDir.path}' — serving /api and /ws only.")
    }
}

@Serializable
data class HealthResponse(val status: String)
