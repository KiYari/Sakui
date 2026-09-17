package com.eeck.server.features.chat.service

import com.eeck.server.core.ids.ChatId
import com.eeck.server.core.ids.UserId
import com.eeck.server.features.chat.dto.Admission
import com.eeck.server.features.chat.dto.AdmissionStatus
import com.eeck.server.features.chat.dto.ChatCloseReasons
import com.eeck.server.features.chat.dto.ErrorFrame
import com.eeck.server.features.chat.dto.HostChanged
import com.eeck.server.features.chat.dto.JoinRequest
import com.eeck.server.features.chat.dto.JoinRequestCancelled
import com.eeck.server.features.chat.dto.MessageOut
import com.eeck.server.features.chat.dto.OutboundFrame
import com.eeck.server.features.chat.dto.ParticipantJoined
import com.eeck.server.features.chat.dto.ParticipantLeft
import com.eeck.server.features.chat.model.Participant
import com.eeck.server.features.chat.model.RoomLimits
import io.ktor.server.websocket.sendSerialized
import io.ktor.websocket.close
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

enum class JoinResult {
    /** In the room — admitted or waiting; the [Admission] frame already told the client which. */
    JOINED,
    ROOM_FULL,

    /** The room was shut down because its link was deleted. */
    ROOM_DELETED,

    /** Internal: this actor had already stopped; [ChatRoomRegistry] retries against a fresh one. */
    ACTOR_STOPPED,
}

/**
 * One coroutine per room, consuming commands from [commands] strictly
 * sequentially — this is the *only* code that ever touches room state.
 * No Mutex: join/leave/relay racing each other is structurally impossible
 * rather than merely untested, because there is only ever one reader making
 * decisions about room state.
 *
 * **Admission.** The first connection into an empty room is admitted and
 * becomes the host. Everyone after that waits until the host admits them.
 * While waiting, a connection receives nothing from the room except frames the
 * host addresses to it, and can send only to the host — enough to exchange
 * keys and profiles so the host knows who is asking, not enough to read the
 * conversation. The host is always the longest-present admitted member, so
 * when the host leaves the role passes on without anyone holding a room hostage.
 *
 * The actor stops (and closes its channel) once the last member leaves.
 * [ChatRoomRegistry] compare-and-removes this exact instance from its map in
 * [onEmpty] — a `tryJoin` that lands on an already-closed actor fails and the
 * caller retries against a fresh one.
 *
 * One narrow race is a known, accepted trade-off of the channel-based design:
 * a `Join` whose `send()` lands in the buffer in the handful of instructions
 * between this actor deciding it's empty and calling [Channel.close] will still
 * be processed even though [onEmpty] has by then evicted this actor — that one
 * joiner ends up alone in an orphaned room. It self-heals on the client's
 * reconnect-with-backoff and needs a leave and a join for the same chatId
 * within nanoseconds of each other; not worth a second lock to close.
 */
class ChatRoomActor(
    private val chatId: ChatId,
    scope: CoroutineScope,
    private val limits: RoomLimits = RoomLimits(),
    private val onEmpty: (ChatId, ChatRoomActor) -> Unit,
) {
    private sealed interface RoomCommand {
        class Join(val participant: Participant, val result: CompletableDeferred<JoinResult>) : RoomCommand
        class Leave(val participant: Participant) : RoomCommand
        class Relay(val from: Participant, val body: JsonElement, val to: UserId?) : RoomCommand
        class Admit(val from: Participant, val target: UserId) : RoomCommand
        class Reject(val from: Participant, val target: UserId) : RoomCommand
        data object Terminate : RoomCommand
    }

    /** Mutable only inside [run]; the list order is arrival order, which is what makes "oldest" well defined. */
    private class Member(var participant: Participant, var admitted: Boolean) {
        val userId: UserId get() = participant.userId
    }

    private val commands = Channel<RoomCommand>(Channel.UNLIMITED)
    private val members = mutableListOf<Member>()
    private var terminated = false

    init {
        scope.launch { run() }
    }

    suspend fun tryJoin(participant: Participant): JoinResult {
        val result = CompletableDeferred<JoinResult>()
        return try {
            commands.send(RoomCommand.Join(participant, result))
            result.await()
        } catch (_: ClosedSendChannelException) {
            JoinResult.ACTOR_STOPPED
        }
    }

    suspend fun leave(participant: Participant) {
        runCatching { commands.send(RoomCommand.Leave(participant)) }
    }

    suspend fun relay(from: Participant, body: JsonElement, to: UserId?) {
        runCatching { commands.send(RoomCommand.Relay(from, body, to)) }
    }

    suspend fun admit(from: Participant, target: UserId) {
        runCatching { commands.send(RoomCommand.Admit(from, target)) }
    }

    suspend fun reject(from: Participant, target: UserId) {
        runCatching { commands.send(RoomCommand.Reject(from, target)) }
    }

    /** Non-suspending so it can be called from plain request-handling code; the channel is unbounded. */
    fun terminate() {
        commands.trySend(RoomCommand.Terminate)
    }

    private suspend fun run() {
        for (command in commands) {
            when (command) {
                is RoomCommand.Join -> command.result.complete(onJoin(command.participant))
                is RoomCommand.Leave -> onLeave(command.participant)
                is RoomCommand.Relay -> onRelay(command.from, command.body, command.to)
                is RoomCommand.Admit -> onAdmit(command.from, command.target)
                is RoomCommand.Reject -> onReject(command.from, command.target)
                RoomCommand.Terminate -> onTerminate()
            }
        }
    }

    private fun host(): Member? = members.firstOrNull { it.admitted }

    private fun memberOf(participant: Participant): Member? = members.firstOrNull { it.participant == participant }

    private suspend fun onJoin(newcomer: Participant): JoinResult {
        if (terminated) return JoinResult.ROOM_DELETED

        // Ids are proven key fingerprints, so a second connection with the same id
        // holds the same private key: a reconnect (the old socket just hasn't timed
        // out yet) or the same identity opened twice. It takes over the old slot —
        // position and admission included — so a reconnecting host stays host and a
        // reconnecting member isn't sent back to the waiting room. The displaced
        // connection is told why it closed, which makes any takeover loud.
        val existing = members.firstOrNull { it.userId == newcomer.userId }
        if (existing != null) {
            val old = existing.participant
            existing.participant = newcomer
            old.sendQuietly(ErrorFrame("REPLACED", "This chat was opened in another tab or window."))
            runCatching { old.session.close(ChatCloseReasons.REPLACED) }
            val hostId = host()!!.userId
            if (existing.admitted) {
                newcomer.sendQuietly(Admission(AdmissionStatus.ADMITTED, hostId.value))
                // Peers re-send their keys on this event, and the fresh connection needs them.
                admittedExcept(existing).forEach { peer ->
                    peer.participant.sendQuietly(ParticipantJoined(newcomer.userId.value))
                    newcomer.sendQuietly(ParticipantJoined(peer.userId.value))
                }
                if (hostId == newcomer.userId) sendPendingRequestsTo(existing)
            } else {
                newcomer.sendQuietly(Admission(AdmissionStatus.PENDING, hostId.value))
                host()!!.participant.sendQuietly(JoinRequest(newcomer.userId.value))
            }
            return JoinResult.JOINED
        }

        val host = host()
        if (host == null) {
            members += Member(newcomer, admitted = true)
            newcomer.sendQuietly(Admission(AdmissionStatus.ADMITTED, newcomer.userId.value))
            return JoinResult.JOINED
        }

        if (members.size >= limits.maxMembers || members.count { !it.admitted } >= limits.maxPending) {
            return JoinResult.ROOM_FULL
        }
        members += Member(newcomer, admitted = false)
        newcomer.sendQuietly(Admission(AdmissionStatus.PENDING, host.userId.value))
        host.participant.sendQuietly(JoinRequest(newcomer.userId.value))
        return JoinResult.JOINED
    }

    private suspend fun onLeave(participant: Participant) {
        // A replaced or rejected connection's route still calls leave() on its way
        // out; it is already gone from the room, so there is nothing to announce.
        val member = memberOf(participant) ?: return
        val previousHost = host()
        members.remove(member)

        if (members.isEmpty()) {
            commands.close()
            onEmpty(chatId, this)
            return
        }

        if (member.admitted) {
            admittedExcept(null).forEach { it.participant.sendQuietly(ParticipantLeft(member.userId.value)) }
        } else {
            previousHost?.participant?.sendQuietly(JoinRequestCancelled(member.userId.value))
        }

        if (member !== previousHost) return

        // Only waiting members are left: promote the one who has waited longest.
        // They join a room with nobody in it, so there is nothing to protect by
        // leaving them all stuck in front of a door no one can open.
        val newHost = host() ?: members.first().also {
            it.admitted = true
            it.participant.sendQuietly(Admission(AdmissionStatus.ADMITTED, it.userId.value))
        }
        members.forEach { it.participant.sendQuietly(HostChanged(newHost.userId.value)) }
        sendPendingRequestsTo(newHost)
    }

    private suspend fun onRelay(from: Participant, body: JsonElement, to: UserId?) {
        val sender = memberOf(from) ?: return
        val frame = MessageOut(sender = sender.userId.value, body = body)

        if (to == null) {
            if (!sender.admitted) return
            admittedExcept(sender).forEach { it.participant.sendQuietly(frame) }
            return
        }

        val target = members.firstOrNull { it.userId == to } ?: return
        if (target === sender) return
        val host = host()
        val allowed = (sender.admitted && target.admitted) ||
            (sender === host && !target.admitted) ||
            (!sender.admitted && target === host)
        if (allowed) target.participant.sendQuietly(frame)
    }

    private suspend fun onAdmit(from: Participant, targetId: UserId) {
        val host = host() ?: return
        if (host.participant != from) return
        val target = members.firstOrNull { it.userId == targetId && !it.admitted } ?: return

        target.admitted = true
        target.participant.sendQuietly(Admission(AdmissionStatus.ADMITTED, host.userId.value))
        admittedExcept(target).forEach { peer ->
            peer.participant.sendQuietly(ParticipantJoined(target.userId.value, announce = true))
            target.participant.sendQuietly(ParticipantJoined(peer.userId.value))
        }
    }

    private suspend fun onReject(from: Participant, targetId: UserId) {
        val host = host() ?: return
        if (host.participant != from) return
        val target = members.firstOrNull { it.userId == targetId && !it.admitted } ?: return

        members.remove(target)
        target.participant.sendQuietly(ErrorFrame("REJECTED", "The host declined your request to join."))
        runCatching { target.participant.session.close(ChatCloseReasons.REJECTED) }
    }

    private suspend fun onTerminate() {
        terminated = true
        val everyone = members.toList()
        members.clear()
        everyone.forEach {
            it.participant.sendQuietly(ErrorFrame("LINK_DELETED", "The creator deleted this chat."))
            runCatching { it.participant.session.close(ChatCloseReasons.LINK_DELETED) }
        }
        // Joins already buffered behind this command still get ROOM_DELETED
        // from onJoin: close() stops new sends but lets the loop drain the rest.
        commands.close()
        onEmpty(chatId, this)
    }

    private fun admittedExcept(excluded: Member?): List<Member> =
        members.filter { it.admitted && it !== excluded }

    private suspend fun sendPendingRequestsTo(host: Member) {
        members.filter { !it.admitted }.forEach { host.participant.sendQuietly(JoinRequest(it.userId.value)) }
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
