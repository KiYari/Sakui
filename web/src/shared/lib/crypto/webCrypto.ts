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

/**
 * Binary payloads (files) deliberately split the hybrid scheme apart instead of
 * reusing [encryptMessage].
 *
 * A text message generates a fresh AES key *per recipient*, which is fine for a
 * few hundred bytes. A file must be encrypted exactly **once** with a single
 * content key that is then RSA-wrapped per recipient: the ciphertext is
 * identical for everyone, so it can be broadcast once instead of re-encrypted
 * and re-sent N times. With fan-out, a 5 MB file in a 4-person room would cost
 * 15 MB of uploads; this way it costs 5.
 */
export async function generateContentKey(): Promise<CryptoKey> {
    // Extractable, unlike the per-message keys: it has to be wrappable for each peer.
    return crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt'])
}

export interface EncryptedBytes {
    iv: string
    ciphertext: ArrayBuffer
}

export async function encryptBytes(key: CryptoKey, data: ArrayBuffer): Promise<EncryptedBytes> {
    const iv = crypto.getRandomValues(new Uint8Array(12))
    const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, data)
    return { iv: toBase64(iv), ciphertext }
}

/**
 * GCM authenticates the whole ciphertext, so the receiver must hold every chunk
 * before this can succeed. That is the point: a per-chunk tag would need its own
 * nonce discipline and extra binding to reject reordered or dropped chunks, and
 * getting that subtly wrong is worse than buffering a bounded file in memory.
 */
export async function decryptBytes(key: CryptoKey, iv: string, ciphertext: ArrayBuffer): Promise<ArrayBuffer> {
    return crypto.subtle.decrypt({ name: 'AES-GCM', iv: fromBase64(iv) }, key, ciphertext)
}

export async function wrapContentKey(key: CryptoKey, peerPublicKey: CryptoKey): Promise<string> {
    return toBase64(await crypto.subtle.wrapKey('raw', key, peerPublicKey, { name: 'RSA-OAEP' }))
}

export async function unwrapContentKey(wrappedKey: string, myPrivateKey: CryptoKey): Promise<CryptoKey> {
    return crypto.subtle.unwrapKey(
        'raw',
        fromBase64(wrappedKey),
        myPrivateKey,
        { name: 'RSA-OAEP' },
        { name: 'AES-GCM' },
        false,
        ['decrypt'],
    )
}

/**
 * A participant's identity: base64url(SHA-256(SPKI DER)), unpadded.
 *
 * Ids used to be self-chosen strings, so anyone could announce a key under
 * someone else's name. Deriving the id from the key makes that impossible to
 * even express: a different key *is* a different id. The server computes the
 * same value independently, so both sides must agree byte for byte (see the
 * contract vector in the tests).
 */
export async function fingerprint(spkiBase64: string): Promise<string> {
    const digest = await crypto.subtle.digest('SHA-256', fromBase64(spkiBase64))
    return toBase64(new Uint8Array(digest)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** Must match the server's context; bumping the version invalidates older proofs. */
const HANDSHAKE_CONTEXT = new TextEncoder().encode('eeck-handshake-v1')

/**
 * Proves possession of the private key: unwraps the server's secret and
 * returns HMAC-SHA256(secret, context ‖ nonce).
 *
 * Unwrapped **as an HMAC key, never as AES**. Message and file keys from peers
 * are unwrapped as AES-GCM keys; if the handshake did the same and *encrypted*
 * a server-chosen nonce, a malicious server could slip a peer's wrapped key into
 * a challenge and receive a valid AES-GCM ciphertext under it — a way to forge
 * messages "from" that peer. As an HMAC key the same bytes are useless for that.
 */
export async function answerChallenge(wrappedKey: string, nonce: string, myPrivateKey: CryptoKey): Promise<string> {
    const secret = await crypto.subtle.unwrapKey(
        'raw',
        fromBase64(wrappedKey),
        myPrivateKey,
        { name: 'RSA-OAEP' },
        { name: 'HMAC', hash: 'SHA-256' },
        false,
        ['sign'],
    )
    const nonceBytes = fromBase64(nonce)
    const message = new Uint8Array(HANDSHAKE_CONTEXT.length + nonceBytes.length)
    message.set(HANDSHAKE_CONTEXT, 0)
    message.set(nonceBytes, HANDSHAKE_CONTEXT.length)
    return toBase64(await crypto.subtle.sign('HMAC', secret, message))
}

export function bytesToBase64(bytes: Uint8Array): string {
    return toBase64(bytes)
}

export function base64ToBytes(b64: string): Uint8Array<ArrayBuffer> {
    return fromBase64(b64)
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
