/**
 * Browser-side E2EE primitives (Web Crypto API only).
 *
 * RSA-OAEP-2048/SHA-256 caps a direct payload at ~190 bytes, so messages use
 * a hybrid scheme instead of chunking: a fresh AES-256-GCM key per message
 * encrypts the actual text (no size limit, authenticated), and only that
 * 32-byte key is RSA-wrapped for the recipient. One RSA operation per
 * message regardless of length, versus dozens-to-hundreds for chunking a
 * message of any real size.
 */

const RSA_PARAMS = {
    name: 'RSA-OAEP',
    modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]),
    hash: 'SHA-256',
} as const satisfies RsaHashedKeyGenParams

export interface ChatKeyPair {
    publicKey: CryptoKey
    privateKey: CryptoKey
}

/**
 * The private key is generated non-extractable — `exportKey`/any other path
 * to its raw bytes will fail for the lifetime of the key. The public key
 * half is always extractable regardless of this flag (verified against
 * Node's Web Crypto implementation, which follows the same spec browsers do).
 */
export async function generateKeyPair(): Promise<ChatKeyPair> {
    // 'wrapKey'/'unwrapKey' (not 'encrypt'/'decrypt') is what the hybrid
    // scheme below actually needs — this key is only ever used to wrap or
    // unwrap the ephemeral per-message AES key, never anything else.
    const pair = await crypto.subtle.generateKey(RSA_PARAMS, false, ['wrapKey', 'unwrapKey'])
    return { publicKey: pair.publicKey, privateKey: pair.privateKey }
}

export async function exportPublicKey(publicKey: CryptoKey): Promise<string> {
    const spki = await crypto.subtle.exportKey('spki', publicKey)
    return toBase64(spki)
}

export async function importPublicKey(spkiBase64: string): Promise<CryptoKey> {
    const bytes = fromBase64(spkiBase64)
    return crypto.subtle.importKey('spki', bytes, RSA_PARAMS, false, ['wrapKey'])
}

/** Wire shape for an encrypted chat message. Travels as the `body` of a transport `message` frame. */
export interface ChatEnvelope {
    kind: 'chat'
    iv: string
    ciphertext: string
    wrappedKey: string
}

export async function encryptMessage(plaintext: string, peerPublicKey: CryptoKey): Promise<ChatEnvelope> {
    const aesKey = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt'])
    const iv = crypto.getRandomValues(new Uint8Array(12))
    const ciphertext = await crypto.subtle.encrypt(
        { name: 'AES-GCM', iv },
        aesKey,
        new TextEncoder().encode(plaintext),
    )
    const wrappedKey = await crypto.subtle.wrapKey('raw', aesKey, peerPublicKey, { name: 'RSA-OAEP' })

    return {
        kind: 'chat',
        iv: toBase64(iv),
        ciphertext: toBase64(ciphertext),
        wrappedKey: toBase64(wrappedKey),
    }
}

export async function decryptMessage(envelope: ChatEnvelope, myPrivateKey: CryptoKey): Promise<string> {
    const aesKey = await crypto.subtle.unwrapKey(
        'raw',
        fromBase64(envelope.wrappedKey),
        myPrivateKey,
        { name: 'RSA-OAEP' },
        { name: 'AES-GCM' },
        false,
        ['decrypt'],
    )
    const plaintext = await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv: fromBase64(envelope.iv) },
        aesKey,
        fromBase64(envelope.ciphertext),
    )
    return new TextDecoder().decode(plaintext)
}

function toBase64(buf: ArrayBuffer | Uint8Array): string {
    const bytes = buf instanceof Uint8Array ? buf : new Uint8Array(buf)
    let binary = ''
    for (const b of bytes) binary += String.fromCharCode(b)
    return btoa(binary)
}

function fromBase64(b64: string): Uint8Array<ArrayBuffer> {
    const binary = atob(b64)
    const bytes = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
    return bytes
}
