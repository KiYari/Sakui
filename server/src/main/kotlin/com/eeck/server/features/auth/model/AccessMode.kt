package com.eeck.server.features.auth.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Who the server lets in, as chosen in the admin panel. */
@Serializable
enum class AccessMode {
    /** A password or a one-time code is required. */
    @SerialName("protected")
    PROTECTED,

    /** Everyone is accepted; passwords and codes are not asked for (and are kept for when this is switched back). */
    @SerialName("open")
    OPEN,
}
