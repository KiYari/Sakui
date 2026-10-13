package com.eeck.server.features.auth.model

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.serialization.Serializable

/** A secret kept only as a salted, deliberately slow hash — never the secret itself. */
@Serializable
data class StoredSecret(val salt: String, val hash: String, val iterations: Int)

/**
 * PBKDF2-HMAC-SHA256, which ships with the JDK. The slowness is the point: a stolen
 * `auth.json` should cost an attacker real time per guess. Only logins and the
 * legacy "password in a header" path pay it; ordinary requests carry a session token.
 */
object PasswordHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private val random = SecureRandom()
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun hash(secret: String): StoredSecret {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return StoredSecret(encoder.encodeToString(salt), encoder.encodeToString(derive(secret, salt, ITERATIONS)), ITERATIONS)
    }

    fun verify(secret: String, stored: StoredSecret): Boolean {
        val expected = decoder.decode(stored.hash)
        return MessageDigest.isEqual(expected, derive(secret, decoder.decode(stored.salt), stored.iterations))
    }

    private fun derive(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

/** Unguessable identifiers and tokens from the platform's strong random source. */
object Tokens {
    private val random = SecureRandom()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    /** 256 bits, URL-safe: a session token or an anti-forgery token. */
    fun random(): String = urlEncoder.encodeToString(ByteArray(32).also(random::nextBytes))

    /** Short public identifier for listing and revoking (not a secret). */
    fun shortId(): String = urlEncoder.encodeToString(ByteArray(6).also(random::nextBytes))

    /** [length] characters drawn uniformly from [alphabet]. */
    fun fromAlphabet(alphabet: String, length: Int): String =
        buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
