package com.eeck.server.core.http

import kotlin.test.Test
import kotlin.test.assertEquals

class LogRedactionTest {

    @Test
    fun `chat ids in paths are redacted`() {
        assertEquals("/api/chat-link/status/<redacted>", redactOpaqueTokens("/api/chat-link/status/nynj5B_DjTCdOoq6ffdDO8rQ"))
        assertEquals("/api/chat-link/<redacted>", redactOpaqueTokens("/api/chat-link/nynj5B_DjTCdOoq6ffdDO8rQ"))
    }

    @Test
    fun `ordinary route words stay readable`() {
        assertEquals("/api/chat-link", redactOpaqueTokens("/api/chat-link"))
        assertEquals("/api/get-users-in-channel", redactOpaqueTokens("/api/get-users-in-channel"))
        assertEquals("/api/health", redactOpaqueTokens("/api/health"))
        assertEquals("/ws", redactOpaqueTokens("/ws"))
    }
}
