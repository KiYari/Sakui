package com.eeck.server.realtime

import kotlinx.coroutines.sync.Mutex

/**
 * At most two [Participant]s. All mutation and reads of [participants] must
 * happen under [mutex] — see [ChatRoomRegistry] for the join/leave protocol
 * that keeps this race-free, including the [closed] flag that prevents a
 * join from resurrecting a room that's mid-teardown.
 */
class ChatRoom(val chatId: String) {
    val mutex = Mutex()
    val participants = mutableListOf<Participant>()
    var closed = false

    /** Caller must hold [mutex]. */
    fun peerOf(participant: Participant): Participant? =
        participants.firstOrNull { it.session !== participant.session }
}
