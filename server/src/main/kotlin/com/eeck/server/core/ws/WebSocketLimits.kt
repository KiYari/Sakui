package com.eeck.server.core.ws

/** Shared across any WS feature (today just chat) — not chat-specific. */
const val DEFAULT_MAX_FRAME_SIZE: Long = 16 * 1024

/**
 * Per-connection frame budget. A 10 MB attachment is ~1,025 frames, so this
 * lets a burst start instantly and then paces it at ~8 MB/s of maximum-size
 * frames — a legitimate send only slows, while a flood is throttled to a rate
 * the relay fan-out can absorb.
 */
const val FRAME_BURST: Int = 512
const val FRAMES_PER_SECOND: Int = 512

/**
 * Concurrent sockets per client address. The per-connection frame budget means
 * nothing if a client can simply open more connections, and every socket may
 * sit in the handshake for its full timeout before doing anything.
 */
const val MAX_CONNECTIONS_PER_CLIENT: Int = 32
