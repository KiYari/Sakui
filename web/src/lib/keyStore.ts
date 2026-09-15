import { generateKeyPair, type ChatKeyPair } from './crypto'

/**
 * Persists the per-chat RSA keypair in IndexedDB as native `CryptoKey`
 * objects via structured clone — never through `exportKey`. This works even
 * for the non-extractable private key: `extractable` only gates the
 * `exportKey`/`wrapKey` APIs, not IndexedDB's structured-clone storage.
 */

const DB_NAME = 'eeck-keystore'
const DB_VERSION = 1
const STORE_NAME = 'keypairs'

interface StoredKeyPair {
    chatId: string
    publicKey: CryptoKey
    privateKey: CryptoKey
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

function getStoredKeyPair(db: IDBDatabase, chatId: string): Promise<StoredKeyPair | undefined> {
    return new Promise((resolve, reject) => {
        const tx = db.transaction(STORE_NAME, 'readonly')
        const request = tx.objectStore(STORE_NAME).get(chatId)
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

/** Returns the keypair stored for `chatId`, generating and persisting a new one on first use. */
export async function getOrCreateKeyPair(chatId: string): Promise<ChatKeyPair> {
    const db = await openDb()
    try {
        const existing = await getStoredKeyPair(db, chatId)
        if (existing) {
            return { publicKey: existing.publicKey, privateKey: existing.privateKey }
        }
        const pair = await generateKeyPair()
        await putKeyPair(db, { chatId, publicKey: pair.publicKey, privateKey: pair.privateKey })
        return pair
    } finally {
        db.close()
    }
}
