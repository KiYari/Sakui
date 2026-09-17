package com.eeck.server.features.session.store

import java.time.Duration

data class SessionStoreConfig(
    val ttl: Duration = Duration.ofHours(24),
    /** Minimum gap between full sweeps of expired entries; sweeps piggyback on [SessionStore.create]. */
    val sweepInterval: Duration = Duration.ofMinutes(1),
)
