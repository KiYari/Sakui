package com.eeck.server.core.http

private val OPAQUE_TOKEN = Regex("^[A-Za-z0-9_-]{16,}$")
private val HAS_UPPER_OR_DIGIT = Regex("[A-Z0-9]")

/**
 * Replaces path segments that look like random tokens with `<redacted>`.
 *
 * A chat id is a bearer secret — knowing it is enough to request entry to the
 * room — and it appears in paths such as `/api/chat-link/status/{id}`. Logs are
 * read by more people, kept longer, and shipped further than the service
 * itself, so they must never carry one.
 *
 * Deliberately generic rather than keyed to the id format: a long run of the
 * base64url alphabet containing an uppercase letter or digit is redacted
 * whatever route it came from, while ordinary words like `get-users-in-channel`
 * stay readable.
 */
fun redactOpaqueTokens(path: String): String =
    path.split('/').joinToString("/") { segment ->
        if (OPAQUE_TOKEN.matches(segment) && HAS_UPPER_OR_DIGIT.containsMatchIn(segment)) "<redacted>" else segment
    }
