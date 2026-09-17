package com.eeck.server.features.chat.service

import com.eeck.server.core.ids.ChatId
import com.eeck.server.core.ids.UserId
import com.eeck.server.features.chat.model.Participant
import com.eeck.server.features.chat.model.RoomLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * Looks up (or creates) the [ChatRoomActor] for a chatId. Owns no room state
 * itself — that's entirely the actor's job — this is just a concurrent map of
 * live room handles.
 */
class ChatRoomRegistry(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val limits: RoomLimits = RoomLimits(),
) {
    private val rooms = ConcurrentHashMap<ChatId, ChatRoomActor>()

    /** Never returns [JoinResult.ACTOR_STOPPED]; that case is retried here. */
    suspend fun join(chatId: ChatId, participant: Participant): JoinResult {
        while (true) {
            val actor = rooms.computeIfAbsent(chatId) { newActor(chatId) }
            val result = actor.tryJoin(participant)
            // Landed on an actor that shut down between computeIfAbsent and tryJoin — retry against a fresh one.
            if (result != JoinResult.ACTOR_STOPPED) return result
        }
    }

    suspend fun leave(chatId: ChatId, participant: Participant) {
        rooms[chatId]?.leave(participant)
    }

    suspend fun relay(chatId: ChatId, from: Participant, body: JsonElement, to: UserId? = null) {
        rooms[chatId]?.relay(from, body, to)
    }

    suspend fun admit(chatId: ChatId, from: Participant, target: UserId) {
        rooms[chatId]?.admit(from, target)
    }

    suspend fun reject(chatId: ChatId, from: Participant, target: UserId) {
        rooms[chatId]?.reject(from, target)
    }

    /** Disconnects everyone in the room; used when its link is deleted. */
    fun closeRoom(chatId: ChatId) {
        rooms[chatId]?.terminate()
    }

    /** Test/diagnostic hook: whether a room currently exists for [chatId]. */
    fun hasRoom(chatId: ChatId): Boolean = rooms.containsKey(chatId)

    private fun newActor(chatId: ChatId): ChatRoomActor =
        ChatRoomActor(chatId, scope, limits) { id, self -> rooms.remove(id, self) }
}
