package com.eeck.server.realtime

import io.ktor.server.websocket.DefaultWebSocketServerSession

data class Participant(val userId: String, val session: DefaultWebSocketServerSession)
