package com.eeck.server.features.chat.model

import com.eeck.server.core.ids.UserId
import io.ktor.server.websocket.DefaultWebSocketServerSession

data class Participant(val userId: UserId, val session: DefaultWebSocketServerSession)
