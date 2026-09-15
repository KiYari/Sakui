package com.eeck.server.features.chat.service

import com.eeck.server.core.ids.ChatId
import com.eeck.server.features.chat.dto.MessageOut
import com.eeck.server.features.chat.dto.OutboundFrame
import com.eeck.server.features.chat.dto.ParticipantJoined
import com.eeck.server.features.chat.dto.ParticipantLeft
import com.eeck.server.features.chat.model.Participant
import io.ktor.server.websocket.sendSerialized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * One coroutine per room, consuming commands from [commands] strictly
 * sequentially — this is the *only* code that ever touches `participants`.
 * No Mutex: join/leave/relay racing each other is structurally impossible
 * rather than merely untested, because there is only ever one reader making
 * decisions about room state.
 *
 * The actor stops (and closes its channel) once the last participant
 * leaves. [ChatRoomRegistry] compare-and-removes this exact instance from
 * its map in [onEmpty] — a `tryJoin` that lands on an already-closed actor
 * fails and the caller retries against a fresh one, the same "don't
 * resurrect a dying room" protection the previous Mutex-based version got
 * from a `closed` flag checked under the same lock as the participant list.
 *
 * One narrow race is a known, accepted trade-off of the channel-based
 * design rather than the lock-based one: a `Join` whose `send()` lands in
 * the buffer in the handful of instructions between this actor deciding
 * it's empty and calling [Channel.close] will still be processed (`close`
 * doesn't discard already-buffered elements) even though [onEmpty] has by
 * then evicted this actor from the registry — that one joiner ends up alone
 * in an orphaned room no one else can reach. It self-heals on the client's
 * existing reconnect-with-backoff and only matters if a leave and a join
 * for the *same* chatId land within nanoseconds of each other on different
 * threads — several orders of magnitude narrower than the network-timed
 * join race this rewrite actually targets, and not worth a second lock to
 * close (that would just reintroduce the thing this pattern replaces).
 */
class ChatRoomActor(
    private val chatId: ChatId,
    scope: CoroutineScope,
    private val onEmpty: (ChatId, ChatRoomActor) -> Unit,
) {
    private sealed interface RoomCommand {
        class Join(val participant: Participant, val ack: CompletableDeferred<Unit>) : RoomCommand
        class Leave(val participant: Participant) : RoomCommand
        class Relay(val from: Participant, val body: JsonElement) : RoomCommand
    }

    private val commands = Channel<RoomCommand>(Channel.UNLIMITED)

    init {
        scope.launch { run() }
    }

    /** False means this actor already shut down (its channel is closed) — the caller should retry against a fresh one. */
    suspend fun tryJoin(participant: Participant): Boolean {
        val ack = CompletableDeferred<Unit>()
        return try {
            commands.send(RoomCommand.Join(participant, ack))
            ack.await()
            true
        } catch (_: ClosedSendChannelException) {
            false
        }
    }

    suspend fun leave(participant: Participant) {
        runCatching { commands.send(RoomCommand.Leave(participant)) }
    }

    suspend fun relay(from: Participant, body: JsonElement) {
        runCatching { commands.send(RoomCommand.Relay(from, body)) }
    }

    private suspend fun run() {
        val participants = mutableListOf<Participant>()
        for (command in commands) {
            when (command) {
                is RoomCommand.Join -> {
                    val existing = participants.toList()
                    participants += command.participant
                    existing.forEach { it.sendQuietly(ParticipantJoined(command.participant.userId.value)) }
                    existing.forEach { peer -> command.participant.sendQuietly(ParticipantJoined(peer.userId.value)) }
                    command.ack.complete(Unit)
                }
                is RoomCommand.Leave -> {
                    participants.remove(command.participant)
                    participants.forEach { it.sendQuietly(ParticipantLeft(command.participant.userId.value)) }
                    if (participants.isEmpty()) {
                        commands.close()
                        onEmpty(chatId, this@ChatRoomActor)
                    }
                }
                is RoomCommand.Relay -> {
                    participants
                        .filter { it.session !== command.from.session }
                        .forEach { it.sendQuietly(MessageOut(sender = command.from.userId.value, body = command.body)) }
                }
            }
        }
    }
}

/**
 * A peer can disconnect between a decision being made about it and actually
 * sending to it. That's expected, not a failure of *our* connection, so a
 * send failure here is swallowed rather than propagated.
 */
private suspend fun Participant.sendQuietly(frame: OutboundFrame) {
    runCatching { session.sendSerialized<OutboundFrame>(frame) }
}
