import { generateKeyPair, type ChatKeyPair } from '../crypto'

/**
 * Persists identity keypairs in IndexedDB as native `CryptoKey` objects via
 * structured clone — never through `exportKey`. This works even for the
 * non-extractable private key: `extractable` only gates the `exportKey`/
 * `wrapKey` APIs, not IndexedDB's structured-clone storage.
 *
 * Entries are keyed `<chatId>:<tabScope>`. A key outliving its chat is a
 * liability with no use — the link is gone, and the key only identifies this
 * browser — so entries are deleted when their chat ends and pruned by age
 * otherwise (links expire after 24h, so nothing older can still be in use).
 */

const DB_NAME = 'eeck-keystore'
const DB_VERSION = 1
const STORE_NAME = 'keypairs'

export const KEY_MAX_AGE_MS = 48 * 60 * 60 * 1000

interface StoredKeyPair {
    /** The store's keyPath; holds the full `<chatId>:<tabScope>` scope key despite the name. */
    chatId: string
    publicKey: CryptoKey
    privateKey: CryptoKey
    /** Missing on entries written before cleanup existed; those count as expired. */
    createdAt?: number
}

function openDb(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
        const request = indexedDB.open(DB_NAME, DB_VERSION)
        request.onupgradeneeded = () => {
            request.result.createObjectStore(STORE_NAME, { keyPath: 'chatId' })
        }
        request.onsuccess = () => resolve(request.result)
        request.onerror = () => reject(request.error)
    })
}

function getStoredKeyPair(db: IDBDatabase, scopeKey: string): Promise<StoredKeyPair | undefined> {
    return new Promise((resolve, reject) => {
        const tx = db.transaction(STORE_NAME, 'readonly')
        const request = tx.objectStore(STORE_NAME).get(scopeKey)
        request.onsuccess = () => resolve(request.result as StoredKeyPair | undefined)
        request.onerror = () => reject(request.error)
    })
}

function putKeyPair(db: IDBDatabase, entry: StoredKeyPair): Promise<void> {
    return new Promise((resolve, reject) => {
        const tx = db.transaction(STORE_NAME, 'readwrite')
        tx.objectStore(STORE_NAME).put(entry)
        tx.oncomplete = () => resolve()
        tx.onerror = () => reject(tx.error)
    })
}

/** Deletes every entry `shouldDelete` accepts, in one transaction. */
function deleteWhere(db: IDBDatabase, shouldDelete: (entry: StoredKeyPair) => boolean): Promise<void> {
    return new Promise((resolve, reject) => {
        const tx = db.transaction(STORE_NAME, 'readwrite')
        const request = tx.objectStore(STORE_NAME).openCursor()
        request.onsuccess = () => {
            const cursor = request.result
            if (!cursor) return
            if (shouldDelete(cursor.value as StoredKeyPair)) cursor.delete()
            cursor.continue()
        }
        tx.oncomplete = () => resolve()
        tx.onerror = () => reject(tx.error)
    })
}

async function withDb<T>(action: (db: IDBDatabase) => Promise<T>): Promise<T> {
    const db = await openDb()
    try {
        return await action(db)
    } finally {
        db.close()
    }
}

/**
 * Returns the keypair stored under `scopeKey`, generating and persisting a new
 * one on first use. Also sweeps out expired keys, since opening a chat is the
 * one moment this code reliably runs.
 */
export async function getOrCreateKeyPair(scopeKey: string, now: number = Date.now()): Promise<ChatKeyPair> {
    return withDb(async (db) => {
        await deleteWhere(db, (entry) => entry.chatId !== scopeKey && isExpired(entry, now))
        const existing = await getStoredKeyPair(db, scopeKey)
        if (existing) {
            return { publicKey: existing.publicKey, privateKey: existing.privateKey }
        }
        const pair = await generateKeyPair()
        await putKeyPair(db, { chatId: scopeKey, publicKey: pair.publicKey, privateKey: pair.privateKey, createdAt: now })
        return pair
    })
}

/** Forgets one tab's key for a chat (the user left). */
export function deleteKeyPair(scopeKey: string): Promise<void> {
    return withDb((db) => deleteWhere(db, (entry) => entry.chatId === scopeKey))
}

/** Forgets every tab's key for a chat (the chat itself is gone). */
export function deleteKeyPairsForChat(chatId: string): Promise<void> {
    return withDb((db) => deleteWhere(db, (entry) => entry.chatId === chatId || entry.chatId.startsWith(`${chatId}:`)))
}

function isExpired(entry: StoredKeyPair, now: number): boolean {
    return entry.createdAt === undefined || now - entry.createdAt > KEY_MAX_AGE_MS
}
