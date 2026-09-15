package com.eeck.server.features.session.store

import java.time.Duration

data class SessionStoreConfig(
    val ttl: Duration = Duration.ofHours(24),
)
