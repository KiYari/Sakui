package com.eeck.server.features.chat.service

import com.eeck.server.core.ids.UserId
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Turns "a client says its id is X" into "this connection holds the private key
 * whose fingerprint is X".
 *
 * Before this, `userId` was a free-text query parameter: anyone in a room could
 * connect under another participant's id and broadcast their own public key
 * under that name, and every peer silently re-keyed to the attacker. Now the id
 * *is* the key — base64url(SHA-256(SPKI)) — and a connection earns it only by
 * unwrapping a secret sealed to that key.
 *
 * The proof is an HMAC, deliberately not an AES encryption. Clients already
 * unwrap peer-supplied content keys as AES-GCM keys; if the handshake also
 * unwrapped to AES and *encrypted* a server-chosen nonce, a malicious server
 * could put a peer's wrapped message key in a challenge and get back a valid
 * ciphertext under it — a forgery oracle. Unwrapping the same bytes as an HMAC
 * key yields nothing usable against AES-GCM.
 */
class IdentityVerifier(private val random: SecureRandom = SecureRandom()) {

    /** Server-side state of one in-flight handshake. Secret material never leaves this object. */
    class PendingChallenge internal constructor(
        val userId: UserId,
        val wrappedKey: String,
        val nonce: String,
        internal val secret: ByteArray,
        internal val nonceBytes: ByteArray,
    )

    /** Null means the key is unusable (malformed, not RSA, or outside the accepted size). */
    fun challengeFor(spkiBase64: String): PendingChallenge? {
        val der = decode(spkiBase64) ?: return null
        val key = parseRsaPublicKey(der) ?: return null
        if (key.modulus.bitLength() !in MIN_MODULUS_BITS..MAX_MODULUS_BITS) return null
        // Exponent 1 makes "encryption" the identity function — the secret would travel in the clear.
        if (key.publicExponent <= BigInteger.ONE) return null

        val secret = ByteArray(SECRET_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val wrapped = Cipher.getInstance("RSA/ECB/OAEPPadding").run {
            // Must match Web Crypto's RSA-OAEP with hash SHA-256, which also uses MGF1-SHA-256.
            init(Cipher.ENCRYPT_MODE, key, OAEP_SHA256)
            doFinal(secret)
        }

        return PendingChallenge(
            userId = UserId(fingerprint(der)),
            wrappedKey = Base64.getEncoder().encodeToString(wrapped),
            nonce = Base64.getEncoder().encodeToString(nonce),
            secret = secret,
            nonceBytes = nonce,
        )
    }

    fun verify(challenge: PendingChallenge, macBase64: String): Boolean {
        val presented = decode(macBase64) ?: return false
        val expected = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(challenge.secret, "HmacSHA256"))
            update(HANDSHAKE_CONTEXT)
            doFinal(challenge.nonceBytes)
        }
        return MessageDigest.isEqual(expected, presented)
    }

    private fun parseRsaPublicKey(der: ByteArray): RSAPublicKey? =
        runCatching { KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as? RSAPublicKey }
            .getOrNull()

    private fun decode(base64: String): ByteArray? = runCatching { Base64.getDecoder().decode(base64) }.getOrNull()

    companion object {
        /** Bumping the version invalidates proofs computed for any older handshake shape. */
        private val HANDSHAKE_CONTEXT = "eeck-handshake-v1".toByteArray(Charsets.US_ASCII)

        private const val SECRET_BYTES = 32
        private const val NONCE_BYTES = 32
        private const val MIN_MODULUS_BITS = 2048
        private const val MAX_MODULUS_BITS = 4096

        private val OAEP_SHA256 = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

        /** Must match the client's `fingerprint()` byte for byte: SHA-256 over SPKI DER, base64url, unpadded. */
        fun fingerprint(spkiDer: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(spkiDer))
    }
}
