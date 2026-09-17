package com.eeck.server.features.session.model

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The secret that proves "I created this link". Returned once, from the create
 * call, and kept by the creator's browser; the server stores only its SHA-256.
 *
 * The chat id alone can't authorize deletion: it is shared with everyone
 * invited, so any participant — or anyone the link leaked to — could otherwise
 * destroy the chat. A hash (not the token) is stored so that a memory dump of
 * the store can't be replayed as a delete.
 */
object OwnerToken {
    private const val TOKEN_BYTES = 32

    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun hash(token: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

    /** Constant-time, so response timing can't be used to guess the stored hash byte by byte. */
    fun matches(presented: String, storedHash: String): Boolean =
        MessageDigest.isEqual(hash(presented).toByteArray(Charsets.US_ASCII), storedHash.toByteArray(Charsets.US_ASCII))
}
