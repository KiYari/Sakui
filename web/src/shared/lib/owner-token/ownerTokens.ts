import { browserStorage, keysWithPrefix, readJson, removeKey, writeJson, type KeyValueStorage } from '../local-store'

const PREFIX = 'eeck-owner-token:'

/** Links live 24h on the server; a little slack covers clock skew before the token is useless anyway. */
const MAX_AGE_MS = 26 * 60 * 60 * 1000

interface StoredToken {
    token: string
    createdAt: number
}

/**
 * The token that lets this browser delete a link it created. Kept in
 * `localStorage` rather than the URL: the link is shared with everyone, the
 * right to destroy it must not be.
 */
export function rememberOwnerToken(
    chatId: string,
    token: string,
    storage: KeyValueStorage | undefined = browserStorage(),
    now: number = Date.now(),
): void {
    pruneOwnerTokens(storage, now)
    writeJson(storage, PREFIX + chatId, { token, createdAt: now } satisfies StoredToken)
}

export function ownerTokenFor(
    chatId: string,
    storage: KeyValueStorage | undefined = browserStorage(),
    now: number = Date.now(),
): string | null {
    const stored = readJson(storage, PREFIX + chatId)
    if (!isStoredToken(stored) || now - stored.createdAt > MAX_AGE_MS) return null
    return stored.token
}

export function forgetOwnerToken(chatId: string, storage: KeyValueStorage | undefined = browserStorage()): void {
    removeKey(storage, PREFIX + chatId)
}

function pruneOwnerTokens(storage: KeyValueStorage | undefined, now: number): void {
    for (const key of keysWithPrefix(storage, PREFIX)) {
        const stored = readJson(storage, key)
        if (!isStoredToken(stored) || now - stored.createdAt > MAX_AGE_MS) removeKey(storage, key)
    }
}

function isStoredToken(value: unknown): value is StoredToken {
    return (
        typeof value === 'object' &&
        value !== null &&
        typeof (value as StoredToken).token === 'string' &&
        typeof (value as StoredToken).createdAt === 'number'
    )
}
