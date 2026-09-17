import {
    answerChallenge,
    decryptBytes,
    decryptMessage,
    encryptBytes,
    encryptMessage,
    exportPublicKey,
    fingerprint,
    generateContentKey,
    generateKeyPair,
    importPublicKey,
    unwrapContentKey,
    wrapContentKey,
    type ChatEnvelope,
    type ChatKeyPair,
    type EncryptedBytes,
} from './webCrypto'

/**
 * The crypto surface the session layer actually depends on.
 *
 * This exists for testability, not for abstraction's sake: a test or a
 * Storybook story can drive a full session — key announcement, fan-out
 * encryption, decrypt-on-receive — against a stub that needs neither real
 * Web Crypto nor IndexedDB, and runs in microseconds instead of generating
 * 2048-bit RSA keys.
 */
export interface CryptoProvider {
    generateKeyPair(): Promise<ChatKeyPair>
    exportPublicKey(publicKey: CryptoKey): Promise<string>
    importPublicKey(spkiBase64: string): Promise<CryptoKey>
    encryptMessage(plaintext: string, peerPublicKey: CryptoKey): Promise<ChatEnvelope>
    decryptMessage(envelope: ChatEnvelope, myPrivateKey: CryptoKey): Promise<string>

    /** Binary attachments: one content key per file, wrapped per recipient. */
    generateContentKey(): Promise<CryptoKey>
    encryptBytes(key: CryptoKey, data: ArrayBuffer): Promise<EncryptedBytes>
    decryptBytes(key: CryptoKey, iv: string, ciphertext: ArrayBuffer): Promise<ArrayBuffer>
    wrapContentKey(key: CryptoKey, peerPublicKey: CryptoKey): Promise<string>
    unwrapContentKey(wrappedKey: string, myPrivateKey: CryptoKey): Promise<CryptoKey>

    /** Identity: the key fingerprint, and the server handshake proving the key is ours. */
    fingerprint(spkiBase64: string): Promise<string>
    answerChallenge(wrappedKey: string, nonce: string, myPrivateKey: CryptoKey): Promise<string>
}

/** The real implementation — Web Crypto, used everywhere outside tests. */
export const webCryptoProvider: CryptoProvider = {
    generateKeyPair,
    exportPublicKey,
    importPublicKey,
    encryptMessage,
    decryptMessage,
    generateContentKey,
    encryptBytes,
    decryptBytes,
    wrapContentKey,
    unwrapContentKey,
    fingerprint,
    answerChallenge,
}
