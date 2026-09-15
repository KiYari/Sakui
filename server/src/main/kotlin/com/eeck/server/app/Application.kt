package com.eeck.server.app

import com.eeck.server.core.errors.ErrorResponse
import com.eeck.server.features.chat.chatWebSocket
import com.eeck.server.features.session.CREATE_LINK_RATE_LIMIT
import com.eeck.server.features.session.sessionRoutes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes

fun main() {
    val appModule = AppModule()
    embeddedServer(Netty, port = appModule.config.port) { module(appModule) }.start(wait = true)
}

fun Application.module(app: AppModule = AppModule()) {
    install(ContentNegotiation) {
        json()
    }
    install(CORS) {
        allowHost(app.config.corsAllowedHost)
        allowHeader(HttpHeaders.ContentType)
    }
    install(CallLogging)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.message ?: "Internal server error"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse("Not found"))
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
        }
    }

    routing {
        route("/api") {
            get("/health") {
                call.respond(HealthResponse(status = "ok"))
            }
            sessionRoutes(app.sessionService)
        }
        chatWebSocket(app.chatRoomRegistry, app.sessionService)
    }
}

@Serializable
data class HealthResponse(val status: String)
