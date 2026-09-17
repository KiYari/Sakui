package com.eeck.server.features.chat

import com.eeck.server.core.ids.ChatId
import com.eeck.server.core.ws.ConnectionLimiter
import com.eeck.server.features.chat.dto.Admission
import com.eeck.server.features.chat.dto.AdmissionStatus
import com.eeck.server.features.chat.dto.Admit
import com.eeck.server.features.chat.dto.Challenge
import com.eeck.server.features.chat.dto.ChatCloseReasons
import com.eeck.server.features.chat.dto.ErrorFrame
import com.eeck.server.features.chat.dto.Hello
import com.eeck.server.features.chat.dto.HostChanged
import com.eeck.server.features.chat.dto.InboundFrame
import com.eeck.server.features.chat.dto.JoinRequest
import com.eeck.server.features.chat.dto.JoinRequestCancelled
import com.eeck.server.features.chat.dto.MessageIn
import com.eeck.server.features.chat.dto.MessageOut
import com.eeck.server.features.chat.dto.ParticipantJoined
import com.eeck.server.features.chat.dto.ParticipantLeft
import com.eeck.server.features.chat.dto.Proof
import com.eeck.server.features.chat.dto.Reject
import com.eeck.server.features.chat.model.RoomLimits
import com.eeck.server.features.chat.port.SessionLookup
import com.eeck.server.features.chat.service.ChatRoomRegistry
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.server.testing.testApplication
import io.ktor.websocket.close
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatWebSocketTest {

    private val alice = TestIdentity.nth(0)
    private val bob = TestIdentity.nth(1)
    private val carol = TestIdentity.nth(2)

    @Test
    fun `messages are relayed to every other admitted participant, labelled with the proven sender id`() = testApplication {
        application { testChatModule(ChatRoomRegistry()) }
        val (a, b, c) = wsClient().admittedRoom("room-msg", alice, bob, carol)

        c.sendSerialized<InboundFrame>(MessageIn(JsonPrimitive("hi both")))

        assertEquals(MessageOut(carol.userId, JsonPrimitive("hi both")), a.expect<MessageOut>())
        assertEquals(MessageOut(carol.userId, JsonPrimitive("hi both")), b.expect<MessageOut>())
        c.expectSilence()
    }

    @Test
    fun `the first connection hosts, later ones wait, and only an admission lets them in`() = testApplication {
        application { testChatModule(ChatRoomRegistry()) }
        val ws = wsClient()

        val (a, hostAdmission) = ws.joinAs("room-admit", alice)
        assertEquals(Admission(AdmissionStatus.ADMITTED, alice.userId), hostAdmission)

        val (b, bobAdmission) = ws.joinAs("room-admit", bob)
        assertEquals(Admission(AdmissionStatus.PENDING, alice.userId), bobAdmission)
        assertEquals(JoinRequest(bob.userId), a.expect<JoinRequest>())

        a.sendSerialized<InboundFrame>(Admit(bob.userId))

        assertEquals(Admission(AdmissionStatus.ADMITTED, alice.userId), b.expect<Admission>())
        assertEquals(ParticipantJoined(alice.userId, announce = false), b.expect<ParticipantJoined>())
        assertEquals(ParticipantJoined(bob.userId, announce = true), a.expect<ParticipantJoined>())
    }

    @Test
    fun `a waiting joiner receives no room traffic and can reach nobody but the host`() = testApplication {
        application { testChatModule(ChatRoomRegistry()) }
        val ws = wsClient()
        val (a, b) = ws.admittedRoom("room-isolation", alice, bob)
        val (c, _) = ws.joinAs("room-isolation", carol)
        a.expect<JoinRequest>()

        // Room traffic, and a member trying to address the waiting joiner directly.
        b.sendSerialized<InboundFrame>(MessageIn(JsonPrimitive("room secret")))
        b.sendSerialized<InboundFrame>(MessageIn(JsonPrimitive("psst"), to = carol.userId))
        assertEquals(MessageOut(bob.userId, JsonPrimitive("room secret")), a.expect<MessageOut>())

        // The waiting joiner broadcasting, and addressing a member who is not the host.
        c.sendSerialized<InboundFrame>(MessageIn(JsonPrimitive("hello room")))
        c.sendSerialized<InboundFrame>(MessageIn(JsonPrimitive("hello bob"), to = bob.userId))

        // The only permitted crossings: joiner -> host, host -> joiner.
        c.sendSerialized<InboundFrame>(MessageIn(JsonPrimitive("my key"), to = alice.userId))
        assertEquals(MessageOut(carol.userId, JsonPrimitive("my key")), a.expect<MessageOut>())
        a.sendSerialized<InboundFrame>(MessageIn(JsonPrimitive("host key"), to = carol.userId))
        assertEquals(MessageOut(alice.userId, JsonPrimitive("host key")), c.expect<MessageOut>())

        c.expectSilence()
        b.expectSilence()
        a.expectSilence()
    }

    @Test
    fun `an addressed message between admitted members reaches only its target`() = testApplication {
        application { testChatModule(ChatRoomRegistry()) }
        val (a, b, c) = wsClient().admittedRoom("room-addressed", alice, bob, carol)

        a.sendSerialized<InboundFrame>(MessageIn(JsonPrimitive("for bob"), to = bob.userId))

        assertEquals(MessageOut(alice.userId, JsonPrimitive("for bob")), b.expect<MessageOut>())
        c.expectSilence()
    }

    @Test
    fun `only the host can admit or reject`() = testApplication {
        application { testChatModule(ChatRoomRegistry()) }
        val ws = wsClient()
        val (a, b) = ws.admittedRoom("room-authority", alice, bob)
        val (c, _) = ws.joinAs("room-authority", carol)
        a.expect<JoinRequest>()

        b.sendSerialized<InboundFrame>(Admit(carol.userId))
        b.sendSerialized<InboundFrame>(Reject(carol.userId))
        c.expectSilence()

        a.sendSerialized<InboundFrame>(Admit(carol.userId))
        assertEquals(AdmissionStatus.ADMITTED, c.expect<Admission>().status)
    }

    @Test
    fun `a rejected joiner is told why and disconnected, and the host is not told they cancelled`() = testApplication {
        val registry = ChatRoomRegistry()
        application { testChatModule(registry) }
        val ws = wsClient()
        val (a, _) = ws.joinAs("room-reject", alice)
        val (b, _) = ws.joinAs("room-reject", bob)
        a.expect<JoinRequest>()

        a.sendSerialized<InboundFrame>(Reject(bob.userId))

        assertEquals("REJECTED", b.expect<ErrorFrame>().code)
        assertEquals(ChatCloseReasons.REJECTED.code, b.closeReason.await()?.code)
        a.expectSilence()
    }

    @Test
    fun `a joiner who gives up waiting withdraws their request`() = testApplication {
        application { testChatModule(ChatRoomRegistry()) }
        val ws = wsClient()
        val (a, _) = ws.joinAs("room-cancel", alice)
        val (b, _) = ws.joinAs("room-cancel", bob)
        a.expect<JoinRequest>()

        b.close()

        assertEquals(JoinRequestCancelled(bob.userId), a.expect<JoinRequestCancelled>())
    }

    @Test
    fun `when the host leaves the longest-present member takes over and inherits waiting requests`() = testApplication {
        application { testChatModule(ChatRoomRegistry()) }
        val ws = wsClient()
        val (a, b) = ws.admittedRoom("room-handover", alice, bob)
        val (c, _) = ws.joinAs("room-handover", carol)
        a.expect<JoinRequest>()

        a.close()

        assertEquals(ParticipantLeft(alice.userId), b.expect<ParticipantLeft>())
        assertEquals(HostChanged(bob.userId), b.expect<HostChanged>())
        assertEquals(JoinRequest(carol.userId), b.expect<JoinRequest>())
        assertEquals(HostChanged(bob.userId), c.expect<HostChanged>())

        b.sendSerialized<InboundFrame>(Admit(carol.userId))
        assertEquals(Admission(AdmissionStatus.ADMITTED, bob.userId), c.expect<Admission>())
    }

    @Test
    fun `when the host leaves with only waiting joiners left, the longest-waiting one becomes host`() = testApplication {
        application { testChatModule(ChatRoomRegistry()) }
        val ws = wsClient()
        val (a, _) = ws.joinAs("room-promote", alice)
        val (b, _) = ws.joinAs("room-promote", bob)
        val (c, _) = ws.joinAs("room-promote", carol)
        a.expect<JoinRequest>()
        a.expect<JoinRequest>()

        a.close()

        assertEquals(Admission(AdmissionStatus.ADMITTED, bob.userId), b.expect<Admission>())
        assertEquals(HostChanged(bob.userId), b.expect<HostChanged>())
        assertEquals(JoinRequest(carol.userId), b.expect<JoinRequest>())
        assertEquals(HostChanged(bob.userId), c.expect<HostChanged>())
    }

    @Test
    fun `joins beyond the room caps are refused`() = testApplication {
        application { testChatModule(ChatRoomRegistry(limits = RoomLimits(maxMembers = 10, maxPending = 1))) }
        val ws = wsClient()
        ws.joinAs("room-full", alice)
        ws.joinAs("room-full", bob)

        val c = ws.open("room-full")
        c.authenticateAs(carol)

        assertEquals("ROOM_FULL", c.expect<ErrorFrame>().code)
        assertEquals(ChatCloseReasons.ROOM_FULL.code, c.closeReason.await()?.code)
    }

    @Test
    fun `closing a room disconnects everyone with link-deleted and removes the room`() = testApplication {
        val registry = ChatRoomRegistry()
        application { testChatModule(registry) }
        val ws = wsClient()
        val (a, b) = ws.admittedRoom("room-deleted", alice, bob)
        val (c, _) = ws.joinAs("room-deleted", carol)

        registry.closeRoom(ChatId("room-deleted"))

        for (session in listOf(b, c)) {
            assertEquals("LINK_DELETED", session.expect<ErrorFrame>().code)
            assertEquals(ChatCloseReasons.LINK_DELETED.code, session.closeReason.await()?.code)
        }
        a.expect<JoinRequest>()
        assertEquals("LINK_DELETED", a.expect<ErrorFrame>().code)
        awaitCondition { !registry.hasRoom(ChatId("room-deleted")) }
    }

    @Test
    fun `a client over its connection cap is refused before the handshake`() = testApplication {
        val limiter = ConnectionLimiter(maxPerKey = 1)
        application { testChatModule(ChatRoomRegistry(), connectionLimiter = limiter) }
        val ws = wsClient()
        val first = ws.open("room-cap")

        val second = ws.open("room-cap")
        assertEquals("TOO_MANY_CONNECTIONS", second.expect<ErrorFrame>().code)
        assertEquals(ChatCloseReasons.TOO_MANY_CONNECTIONS.code, second.closeReason.await()?.code)

        // The slot is released on disconnect, so the cap limits concurrency, not lifetime use.
        first.close()
        delay(300)
        ws.joinAs("room-cap", alice)
    }

    @Test
    fun `missing chatId is rejected as a bad request`() = testApplication {
        application { testChatModule(ChatRoomRegistry()) }

        val session = wsClient().open("")
        assertEquals("BAD_REQUEST", session.expect<ErrorFrame>().code)
        assertEquals(ChatCloseReasons.BAD_REQUEST.code, session.closeReason.await()?.code)
    }

    @Test
    fun `a chatId with no active session is rejected before any handshake`() = testApplication {
        val registry = ChatRoomRegistry()
        application { testChatModule(registry, sessionLookup = SessionLookup { false }) }

        val session = wsClient().open("never-created")

        assertEquals("INVALID_SESSION", session.expect<ErrorFrame>().code)
        assertEquals(ChatCloseReasons.INVALID_SESSION.code, session.closeReason.await()?.code)
        assertFalse(registry.hasRoom(ChatId("never-created")))
    }

    @Test
    fun `presenting someone else's public key without their private key is refused`() = testApplication {
        // The original attack: claim a present participant's identity to substitute keys under their name.
        val registry = ChatRoomRegistry()
        application { testChatModule(registry) }

        val session = wsClient().open("room-impostor")
        session.sendSerialized<InboundFrame>(Hello(alice.spki))
        session.expect<Challenge>()
        // Without alice's private key the attacker can only guess the MAC.
        session.sendSerialized<InboundFrame>(Proof(Base64.getEncoder().encodeToString(ByteArray(32))))

        assertEquals("HANDSHAKE_FAILED", session.expect<ErrorFrame>().code)
        assertEquals(ChatCloseReasons.HANDSHAKE_FAILED.code, session.closeReason.await()?.code)
        assertFalse(registry.hasRoom(ChatId("room-impostor")))
    }

    @Test
    fun `a connection that skips the handshake cannot join or relay anything`() = testApplication {
        val registry = ChatRoomRegistry()
        application { testChatModule(registry) }

        val session = wsClient().open("room-skip")
        session.sendSerialized<InboundFrame>(MessageIn(JsonPrimitive("straight to the payload")))

        assertEquals("HANDSHAKE_FAILED", session.expect<ErrorFrame>().code)
        assertEquals(ChatCloseReasons.HANDSHAKE_FAILED.code, session.closeReason.await()?.code)
        assertFalse(registry.hasRoom(ChatId("room-skip")))
    }

    @Test
    fun `a weak key is refused`() = testApplication {
        application { testChatModule(ChatRoomRegistry()) }

        val session = wsClient().open("room-weak")
        session.sendSerialized<InboundFrame>(Hello(TestIdentity.generate(1024).spki))

        assertEquals("HANDSHAKE_FAILED", session.expect<ErrorFrame>().code)
    }

    @Test
    fun `reconnecting with the same key replaces the old connection and keeps its place in the room`() = testApplication {
        val registry = ChatRoomRegistry()
        application { testChatModule(registry) }
        val ws = wsClient()
        val (aliceOld, b) = ws.admittedRoom("room-replace", alice, bob)

        // Alice was host; the reconnect must neither demote her nor send her back to the waiting room.
        val (aliceNew, admission) = ws.joinAs("room-replace", alice)
        assertEquals(Admission(AdmissionStatus.ADMITTED, alice.userId), admission)
        assertEquals(ParticipantJoined(bob.userId), aliceNew.expect<ParticipantJoined>())

        assertEquals("REPLACED", aliceOld.expect<ErrorFrame>().code)
        assertEquals(ChatCloseReasons.REPLACED.code, aliceOld.closeReason.await()?.code)

        // A fresh, unannounced join notice so bob re-sends his key — and no departure notice
        // once the displaced connection's route runs its leave().
        assertEquals(ParticipantJoined(alice.userId, announce = false), b.expect<ParticipantJoined>())
        delay(300)
        aliceNew.sendSerialized<InboundFrame>(MessageIn(JsonPrimitive("still here")))
        assertEquals(MessageOut(alice.userId, JsonPrimitive("still here")), b.expect<MessageOut>())

        // Still host: a new joiner's request goes to the new connection.
        ws.joinAs("room-replace", carol)
        assertEquals(JoinRequest(carol.userId), aliceNew.expect<JoinRequest>())
    }

    @Test
    fun `the remaining participant is notified when an admitted one disconnects`() = testApplication {
        application { testChatModule(ChatRoomRegistry()) }
        val (a, b) = wsClient().admittedRoom("room-leave", alice, bob)

        b.close()

        assertEquals(ParticipantLeft(bob.userId), a.expect<ParticipantLeft>())
    }

    @Test
    fun `the room is torn down once everyone disconnects`() = testApplication {
        val registry = ChatRoomRegistry()
        application { testChatModule(registry) }
        val sessions = wsClient().admittedRoom("room-cleanup", alice, bob)
        assertTrue(registry.hasRoom(ChatId("room-cleanup")))

        sessions.forEach { it.close() }

        awaitCondition { !registry.hasRoom(ChatId("room-cleanup")) }
    }
}
