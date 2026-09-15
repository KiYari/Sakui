package com.eeck.server.features.chat.service

import com.eeck.server.core.ids.ChatId
import com.eeck.server.features.chat.model.Participant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * Looks up (or creates) the [ChatRoomActor] for a chatId. Owns no
 * participant-list state itself — that's entirely the actor's job — this is
 * just a concurrent map of live room handles.
 */
class ChatRoomRegistry(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val rooms = ConcurrentHashMap<ChatId, ChatRoomActor>()

    suspend fun join(chatId: ChatId, participant: Participant) {
        while (true) {
            val actor = rooms.computeIfAbsent(chatId) { newActor(chatId) }
            if (actor.tryJoin(participant)) return
            // Landed on an actor that already shut down between computeIfAbsent and tryJoin — retry against a fresh one.
        }
    }

    suspend fun leave(chatId: ChatId, participant: Participant) {
        rooms[chatId]?.leave(participant)
    }

    suspend fun relay(chatId: ChatId, from: Participant, body: JsonElement) {
        rooms[chatId]?.relay(from, body)
    }

    /** Test/diagnostic hook: whether a room currently exists for [chatId]. */
    fun hasRoom(chatId: ChatId): Boolean = rooms.containsKey(chatId)

    private fun newActor(chatId: ChatId): ChatRoomActor =
        ChatRoomActor(chatId, scope) { id, self -> rooms.remove(id, self) }
}
