package com.eeck.server.features.auth.service

/**
 * Minimum standards for a secret an admin types in, since a typed secret is only
 * as strong as its author made it. Generated ones need no checking.
 */
object SecretPolicy {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 128

    private val COMMON = setOf(
        "password", "password1", "passw0rd", "12345678", "123456789", "1234567890", "qwertyui", "qwerty123",
        "qwertyuiop", "11111111", "00000000", "abcdefgh", "abc12345", "iloveyou", "admin123", "letmein1",
        "welcome1", "secret12", "secretkey", "changeme", "eeck1234", "пароль12", "пароль123",
    )

    /** A problem with [secret] in words for the person typing it, or null when it is acceptable. */
    fun problemWith(secret: String): String? {
        if (secret.length < MIN_LENGTH) return "Use at least $MIN_LENGTH characters."
        if (secret.length > MAX_LENGTH) return "Use at most $MAX_LENGTH characters."
        if (secret != secret.trim()) return "Leave out spaces at the start and the end."
        if (secret.toSet().size <= 2) return "That repeats too few different characters."
        if (secret.lowercase() in COMMON) return "That is one of the most common passwords."
        if (isSequence(secret)) return "That is a simple sequence."
        return null
    }

    private fun isSequence(secret: String): Boolean {
        if (secret.length < 6) return false
        val steps = secret.zipWithNext { a, b -> b.code - a.code }
        return steps.all { it == 1 } || steps.all { it == -1 }
    }
}
