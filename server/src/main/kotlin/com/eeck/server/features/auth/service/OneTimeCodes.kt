package com.eeck.server.features.auth.service

import com.eeck.server.features.auth.model.Tokens
import java.util.concurrent.ConcurrentHashMap

/** A code as the admin panel lists it. The code itself is not here: only its hash is ever kept. */
data class CodeInfo(
    val id: String,
    val label: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    /** Typed in by the admin rather than generated. */
    val custom: Boolean,
)

sealed interface CreateCodeResult {
    /** [code] is shown to the admin exactly once, here. */
    data class Created(val info: CodeInfo, val code: String) : CreateCodeResult

    data class Invalid(val reason: String) : CreateCodeResult
}

/**
 * Single-use, time-limited codes that let someone in without the server password.
 *
 * - Only SHA-256 hashes are stored, so the list cannot show a code and a memory
 *   dump does not hand them out.
 * - Redeeming is one atomic `remove`: of two simultaneous attempts exactly one wins.
 * - A wrong guess burns nothing; limiting guesses is the caller's job.
 * - Generated codes are looked up in a normalised form (case, dashes and spaces
 *   ignored), because people mistype those; typed ones must match exactly.
 */
class OneTimeCodes(
    private val clock: () -> Long = System::currentTimeMillis,
    /** Refuse a typed code equal to the server password: two doors with one key would be ambiguous. */
    private val clashesWithPassword: (String) -> Boolean = { false },
) {
    private class Entry(val info: CodeInfo)

    private val byHash = ConcurrentHashMap<String, Entry>()

    fun create(typed: String?, ttlMillis: Long, label: String): CreateCodeResult {
        sweep()
        val (code, key) = if (typed == null) {
            val generated = generate()
            generated to hashOf(normalise(generated))
        } else {
            SecretPolicy.problemWith(typed)?.let { return CreateCodeResult.Invalid(it) }
            if (clashesWithPassword(typed)) return CreateCodeResult.Invalid("That is the server password; a code must be different.")
            typed to hashOf(typed)
        }
        val now = clock()
        val info = CodeInfo(
            id = Tokens.shortId(),
            label = label.trim().take(MAX_LABEL),
            createdAtMillis = now,
            expiresAtMillis = now + ttlMillis,
            custom = typed != null,
        )
        // putIfAbsent is the uniqueness check: an identical active code would make redeeming ambiguous.
        if (byHash.putIfAbsent(key, Entry(info)) != null) return CreateCodeResult.Invalid("An active code like that already exists.")
        return CreateCodeResult.Created(info, code)
    }

    /** True if [attempt] was a live code, which is now spent. */
    fun redeem(attempt: String): Boolean {
        val trimmed = attempt.trim()
        if (trimmed.isEmpty()) return false
        val now = clock()
        for (candidate in listOf(trimmed, normalise(trimmed)).distinct()) {
            val entry = byHash.remove(hashOf(candidate)) ?: continue
            if (entry.info.expiresAtMillis > now) return true
            // Expired: it is gone either way, and this attempt does not count.
        }
        return false
    }

    fun active(): List<CodeInfo> {
        sweep()
        return byHash.values.map { it.info }.sortedBy { it.expiresAtMillis }
    }

    fun revoke(id: String): Boolean {
        val key = byHash.entries.firstOrNull { it.value.info.id == id }?.key ?: return false
        return byHash.remove(key) != null
    }

    fun revokeAll() = byHash.clear()

    private fun sweep() {
        val now = clock()
        byHash.entries.removeIf { it.value.info.expiresAtMillis <= now }
    }

    private fun generate(): String {
        val raw = Tokens.fromAlphabet(ALPHABET, CODE_LENGTH)
        return raw.chunked(CODE_LENGTH / 2).joinToString("-")
    }

    private fun normalise(code: String): String = code.filterNot { it == '-' || it.isWhitespace() }.uppercase()

    private fun hashOf(value: String): String = Tokens.sha256Hex(value)

    companion object {
        /** No 0/O, 1/I/L: a code read aloud or off a screen should not be ambiguous. */
        const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        const val CODE_LENGTH = 10
        const val MAX_LABEL = 60
    }
}
