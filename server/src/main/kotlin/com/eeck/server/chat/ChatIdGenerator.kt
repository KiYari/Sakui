package com.eeck.server.chat

import java.security.SecureRandom
import java.util.Base64

/**
 * Generates and validates chat-link ids. Cryptographically strong (SecureRandom,
 * 144 bits of entropy — comfortably above the 128-bit floor) rather than
 * `UUID.randomUUID()`, which is not specified to draw from a CSPRNG.
 *
 * 18 random bytes, base64url-encoded (RFC 4648 §5, unpadded): 18 is a multiple
 * of 3, so the encoding is exactly 24 characters with no padding, giving a
 * clean fixed-length format to validate against.
 */
object ChatIdGenerator {
    private const val ID_BYTES = 18
    const val ID_LENGTH = 24
    private val FORMAT = Regex("^[A-Za-z0-9_-]{$ID_LENGTH}$")

    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun generate(): String {
        val bytes = ByteArray(ID_BYTES)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun isValidFormat(id: String): Boolean = FORMAT.matches(id)
}
