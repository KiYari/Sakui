package com.eeck.server.features.chat.model

/**
 * Per-room caps. Rooms are open-ended by design, but "unlimited" must not mean
 * one link can pin unbounded server memory, or that strangers holding a leaked
 * link can flood the host with join requests.
 */
data class RoomLimits(
    /** Admitted plus waiting connections. */
    val maxMembers: Int = 64,
    /** Waiting connections; kept well below [maxMembers] so a request flood can't crowd out real members. */
    val maxPending: Int = 16,
)
