/**
 * The subset of `Storage` this app uses — small enough that a test can pass a
 * `Map`-backed stand-in, since vitest runs without a DOM.
 */
export interface KeyValueStorage {
    getItem(key: string): string | null
    setItem(key: string, value: string): void
    removeItem(key: string): void
    readonly length: number
    key(index: number): string | null
}

/**
 * `localStorage`, or `undefined` when the browser refuses it (privacy modes,
 * sandboxed frames, blocked site data) — reading the property itself can throw.
 * Everything persisted here is a convenience, so callers degrade to "not
 * remembered" rather than failing.
 */
export function browserStorage(): KeyValueStorage | undefined {
    try {
        return globalThis.localStorage ?? undefined
    } catch {
        return undefined
    }
}

export function readJson(storage: KeyValueStorage | undefined, key: string): unknown {
    if (!storage) return undefined
    try {
        const raw = storage.getItem(key)
        return raw === null ? undefined : (JSON.parse(raw) as unknown)
    } catch {
        return undefined
    }
}

export function writeJson(storage: KeyValueStorage | undefined, key: string, value: unknown): void {
    if (!storage) return
    try {
        storage.setItem(key, JSON.stringify(value))
    } catch {
        // Quota exceeded or storage revoked mid-session: forgetting is the safe failure.
    }
}

export function removeKey(storage: KeyValueStorage | undefined, key: string): void {
    if (!storage) return
    try {
        storage.removeItem(key)
    } catch {
        // See writeJson.
    }
}

/** Every key starting with `prefix`, collected first so callers may remove while iterating. */
export function keysWithPrefix(storage: KeyValueStorage | undefined, prefix: string): string[] {
    if (!storage) return []
    try {
        const keys: string[] = []
        for (let i = 0; i < storage.length; i++) {
            const key = storage.key(i)
            if (key?.startsWith(prefix)) keys.push(key)
        }
        return keys
    } catch {
        return []
    }
}

/** In-memory [KeyValueStorage], for tests and for environments with no usable storage. */
export function memoryStorage(): KeyValueStorage {
    const map = new Map<string, string>()
    return {
        getItem: (key) => map.get(key) ?? null,
        setItem: (key, value) => void map.set(key, value),
        removeItem: (key) => void map.delete(key),
        get length() {
            return map.size
        },
        key: (index) => [...map.keys()][index] ?? null,
    }
}
