package com.eeck.server.realtime

import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

sealed interface JoinOutcome {
    data class Joined(val existingPeer: Participant?) : JoinOutcome
    object RoomFull : JoinOutcome
}

/**
 * All access to a room's participant list goes through here, never through a
 * [ChatRoom] reference held by a caller — that's what keeps join/leave/teardown
 * race-free (see the class doc on [ChatRoom]).
 */
class ChatRoomRegistry {
    private val rooms = ConcurrentHashMap<String, ChatRoom>()

    /**
     * Atomically checks room capacity and adds [participant] if there's room.
     * If the room we fetched was concurrently torn down by a [leave] that
     * emptied it (marked [ChatRoom.closed]), retries against a fresh room
     * instead of resurrecting the dead one.
     */
    suspend fun join(chatId: String, participant: Participant): JoinOutcome {
        while (true) {
            val room = rooms.computeIfAbsent(chatId) { ChatRoom(chatId) }
            val outcome = room.mutex.withLock {
                when {
                    room.closed -> null
                    room.participants.size >= 2 -> JoinOutcome.RoomFull
                    else -> {
                        val existingPeer = room.participants.firstOrNull()
                        room.participants += participant
                        JoinOutcome.Joined(existingPeer)
                    }
                }
            }
            if (outcome != null) return outcome
        }
    }

    /** Removes [participant]; if the room is now empty, marks it closed and evicts it. Returns the remaining peer, if any. */
    suspend fun leave(chatId: String, participant: Participant): Participant? {
        val room = rooms[chatId] ?: return null
        return room.mutex.withLock {
            room.participants.remove(participant)
            val remainingPeer = room.participants.firstOrNull()
            if (room.participants.isEmpty()) {
                room.closed = true
                rooms.remove(chatId, room)
            }
            remainingPeer
        }
    }

    suspend fun peerFor(chatId: String, participant: Participant): Participant? {
        val room = rooms[chatId] ?: return null
        return room.mutex.withLock { room.peerOf(participant) }
    }

    /** Test/diagnostic hook: whether a room currently exists for [chatId]. */
    fun hasRoom(chatId: String): Boolean = rooms.containsKey(chatId)
}
