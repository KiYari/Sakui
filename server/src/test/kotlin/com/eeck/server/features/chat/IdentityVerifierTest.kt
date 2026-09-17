package com.eeck.server.features.chat

import com.eeck.server.features.chat.dto.Challenge
import com.eeck.server.features.chat.service.IdentityVerifier
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityVerifierTest {

    private val verifier = IdentityVerifier()

    /**
     * Contract vector shared with the web client's crypto test. Both sides must
     * derive the same id from the same key bytes, or every handshake fails with
     * a mismatched `welcome`.
     */
    @Test
    fun `fingerprint matches the vector the web client is tested against`() {
        val der = Base64.getDecoder().decode(CONTRACT_SPKI)
        assertEquals(CONTRACT_FINGERPRINT, IdentityVerifier.fingerprint(der))
    }

    @Test
    fun `a client holding the private key passes and is assigned its fingerprint`() {
        val identity = TestIdentity.nth(0)

        val challenge = assertNotNull(verifier.challengeFor(identity.spki))

        assertEquals(identity.userId, challenge.userId.value)
        assertTrue(verifier.verify(challenge, identity.proofFor(challenge.toWire()).mac))
    }

    @Test
    fun `a client that only knows the public key cannot answer`() {
        // The attack being closed: present someone else's (public) key to claim their id.
        val victim = TestIdentity.nth(0)
        val attacker = TestIdentity.nth(1)

        val challenge = assertNotNull(verifier.challengeFor(victim.spki))

        // Unwrapping with the wrong private key fails outright; a guessed MAC fails verification.
        assertTrue(runCatching { attacker.proofFor(challenge.toWire()) }.isFailure)
        assertFalse(verifier.verify(challenge, Base64.getEncoder().encodeToString(ByteArray(32))))
    }

    @Test
    fun `a proof is bound to its own challenge and cannot be replayed onto another`() {
        val identity = TestIdentity.nth(0)
        val first = assertNotNull(verifier.challengeFor(identity.spki))
        val second = assertNotNull(verifier.challengeFor(identity.spki))

        val proofForFirst = identity.proofFor(first.toWire()).mac

        assertTrue(verifier.verify(first, proofForFirst))
        assertFalse(verifier.verify(second, proofForFirst))
    }

    @Test
    fun `weak, malformed, or non-RSA keys are refused before any challenge is issued`() {
        assertNull(verifier.challengeFor(TestIdentity.generate(1024).spki))
        assertNull(verifier.challengeFor("not base64 at all!"))
        assertNull(verifier.challengeFor(Base64.getEncoder().encodeToString(ByteArray(64))))

        val ecKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public.encoded
        assertNull(verifier.challengeFor(Base64.getEncoder().encodeToString(ecKey)))
    }

    @Test
    fun `a malformed proof is simply a failed proof`() {
        val challenge = assertNotNull(verifier.challengeFor(TestIdentity.nth(0).spki))
        assertFalse(verifier.verify(challenge, "%%%"))
        assertFalse(verifier.verify(challenge, ""))
    }

    private fun IdentityVerifier.PendingChallenge.toWire() = Challenge(wrappedKey = wrappedKey, nonce = nonce)

    companion object {
        const val CONTRACT_SPKI =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtLWwXYyBgZrMNMbqZ/6msoZghWaO6EXeQErjL2cRq4Hwm6rjnbLkEkLyW7h6" +
                "FLczX+vTo8Ppnwr1/TXIKWAT13+xWJwQROu4MilAl5dQJHXZRRvco/+PmrZXTAWCtkFI9cvMx0IGiLa9rqylXoEmHdWKOLKnhjLQ" +
                "J96br9jU6EwLIjii95faYMKQCtVxi7TYoA24MVil3ivcdMZK+aY70lpe/dSBn3ge0pDRUIl543r0tdApcdlVzzdk7R+lIzQen+yL" +
                "WuwiDxnkrKg2Nne5w2/T4QABJuQ9BZ9sbu5CRrX+V5dgA4zyoOv3zP3GYczYvJEY5+wLnFbeJdOGwbbFGwIDAQAB"
        const val CONTRACT_FINGERPRINT = "104p6RV0KuYaIUCTWE5Hy8fTY-iykPYhbjq-9NTGSyw"
    }
}
