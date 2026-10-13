package com.eeck.server.features.auth.store

import com.eeck.server.features.auth.model.AccessMode
import com.eeck.server.features.auth.model.StoredSecret
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What survives a restart: the server password (set in the panel) and the access mode. The master key is never stored (it comes
 * from `EECK_MASTER_KEY`, or is made fresh at each start), and codes and sessions live in memory.
 * Older files that still carry a master key entry are read and the entry ignored.
 */
@Serializable
data class AuthState(
    val version: Int = 1,
    /**
     * The server password set from the admin panel, as text: the panel can show it on request, which a
     * hash cannot do. The file is owner-only, next to the settings it protects. When present it replaces
     * `EECK_ACCESS_KEY`.
     */
    val passwordText: String? = null,
    /**
     * A password set by an older version, kept only as a hash. It still works for signing in, but cannot
     * be shown; setting a new password replaces it with [passwordText].
     */
    val password: StoredSecret? = null,
    /**
     * An explicit choice made in the admin panel. Null (never chosen) means "follow the password":
     * protected when one is set, open when not — how the server behaved before this existed.
     */
    val access: AccessMode? = null,
)

/**
 * `auth.json` in the data directory. A damaged file stops the server with a clear
 * message rather than being read as empty: silently forgetting the password would
 * leave the server open or locked, and the operator should decide which.
 */
class AuthFileStore(private val file: File?) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(): AuthState {
        if (file == null || !file.isFile) return AuthState()
        return try {
            json.decodeFromString(AuthState.serializer(), file.readText())
        } catch (e: Exception) {
            throw IllegalStateException(
                "${file.absolutePath} is unreadable (${e.message}). Fix or delete it: deleting it forgets a " +
                    "password set in the admin panel and the generated master key.",
                e,
            )
        }
    }

    /** Written to a temporary file and moved into place, so a crash cannot leave half a file. */
    fun save(state: AuthState) {
        if (file == null) return // memory-only: nothing to persist
        file.absoluteFile.parentFile.mkdirs()
        val temp = File(file.absoluteFile.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(AuthState.serializer(), state))
        try {
            Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX filesystem (Windows); the folder's own permissions apply.
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
