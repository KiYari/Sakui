package com.eeck.server.features.chat

import com.eeck.server.features.chat.dto.Challenge
import com.eeck.server.features.chat.dto.Hello
import com.eeck.server.features.chat.dto.InboundFrame
import com.eeck.server.features.chat.dto.OutboundFrame
import com.eeck.server.features.chat.dto.Proof
import com.eeck.server.features.chat.dto.Welcome
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Plays the *client* side of the handshake with its own JVM crypto, written
 * independently of `IdentityVerifier`. If the two sides drift apart — hash,
 * padding, HMAC context — these tests fail instead of quietly agreeing with
 * themselves.
 */
class TestIdentity private constructor(val keyPair: KeyPair) {
    val spki: String = Base64.getEncoder().encodeToString(keyPair.public.encoded)
    val userId: String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded))

    fun proofFor(challenge: Challenge): Proof {
        val secret = Cipher.getInstance("RSA/ECB/OAEPPadding").run {
            init(
                Cipher.DECRYPT_MODE,
                keyPair.private,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
            )
            doFinal(Base64.getDecoder().decode(challenge.wrappedKey))
        }
        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            update("eeck-handshake-v1".toByteArray(Charsets.US_ASCII))
            doFinal(Base64.getDecoder().decode(challenge.nonce))
        }
        return Proof(Base64.getEncoder().encodeToString(mac))
    }

    companion object {
        private val pool = mutableListOf<TestIdentity>()

        /** RSA-2048 generation is slow enough to matter at 40 participants, so identities are reused across tests. */
        @Synchronized
        fun nth(index: Int): TestIdentity {
            while (pool.size <= index) pool += generate(2048)
            return pool[index]
        }

        fun generate(bits: Int): TestIdentity =
            TestIdentity(KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair())
    }
}

/** Completes the handshake and returns the id the server assigned. */
suspend fun DefaultClientWebSocketSession.authenticateAs(identity: TestIdentity): String {
    sendSerialized<InboundFrame>(Hello(identity.spki))
    val challenge = receiveDeserialized<OutboundFrame>() as Challenge
    sendSerialized<InboundFrame>(identity.proofFor(challenge))
    val welcome = receiveDeserialized<OutboundFrame>() as Welcome
    check(welcome.userId == identity.userId) { "server assigned ${welcome.userId}, expected ${identity.userId}" }
    return welcome.userId
}
