import { describe, expect, it } from 'vitest'
import { decryptMessage, encryptMessage, exportPublicKey, generateKeyPair, importPublicKey } from '../webCrypto'
import { webCryptoProvider } from '../provider'
import { answerChallenge, base64ToBytes, bytesToBase64, fingerprint } from '../webCrypto'

describe('crypto', () => {
    it('generates a non-extractable private key and an extractable public key', async () => {
        const { publicKey, privateKey } = await generateKeyPair()

        expect(publicKey.extractable).toBe(true)
        expect(privateKey.extractable).toBe(false)
        await expect(crypto.subtle.exportKey('pkcs8', privateKey)).rejects.toThrow()
    })

    it('round-trips a message through export/import and encrypt/decrypt', async () => {
        const alice = await generateKeyPair()
        const bob = await generateKeyPair()

        const alicePublicSpki = await exportPublicKey(alice.publicKey)
        const bobsViewOfAlice = await importPublicKey(alicePublicSpki)

        const plaintext = 'hello bob, this is alice'
        const envelope = await encryptMessage(plaintext, bobsViewOfAlice)

        expect(envelope.kind).toBe('chat')
        // Ciphertext must not leak the plaintext in any recognizable form.
        expect(envelope.ciphertext).not.toContain('hello')

        const decrypted = await decryptMessage(envelope, alice.privateKey)
        expect(decrypted).toBe(plaintext)

        // Bob's private key must not be able to open a message encrypted to Alice.
        await expect(decryptMessage(envelope, bob.privateKey)).rejects.toThrow()
    })

    it('handles a message near the 190-byte RSA-OAEP-2048 direct-encryption cap without chunking', async () => {
        const { publicKey, privateKey } = await generateKeyPair()
        const longMessage = 'x'.repeat(5_000) // far beyond what raw RSA-OAEP-2048 could ever encrypt directly

        const envelope = await encryptMessage(longMessage, publicKey)
        const decrypted = await decryptMessage(envelope, privateKey)

        expect(decrypted).toBe(longMessage)
    })

    it('produces a different ciphertext and wrapped key for the same plaintext each time', async () => {
        const { publicKey } = await generateKeyPair()

        const first = await encryptMessage('same message', publicKey)
        const second = await encryptMessage('same message', publicKey)

        expect(first.iv).not.toBe(second.iv)
        expect(first.ciphertext).not.toBe(second.ciphertext)
        expect(first.wrappedKey).not.toBe(second.wrappedKey)
    })
})

describe('webCryptoProvider', () => {
    it('wires the interface to the real primitives (round-trips through the provider only)', async () => {
        const { publicKey, privateKey } = await webCryptoProvider.generateKeyPair()

        const spki = await webCryptoProvider.exportPublicKey(publicKey)
        const imported = await webCryptoProvider.importPublicKey(spki)
        const envelope = await webCryptoProvider.encryptMessage('через провайдер', imported)

        expect(await webCryptoProvider.decryptMessage(envelope, privateKey)).toBe('через провайдер')
    })
})

describe('identity', () => {
    // Same vector as IdentityVerifierTest on the server. If these ever disagree,
    // every handshake fails with a mismatched `welcome`.
    const CONTRACT_SPKI =
        'MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtLWwXYyBgZrMNMbqZ/6msoZghWaO6EXeQErjL2cRq4Hwm6rjnbLkEkLyW7h6' +
        'FLczX+vTo8Ppnwr1/TXIKWAT13+xWJwQROu4MilAl5dQJHXZRRvco/+PmrZXTAWCtkFI9cvMx0IGiLa9rqylXoEmHdWKOLKnhjLQ' +
        'J96br9jU6EwLIjii95faYMKQCtVxi7TYoA24MVil3ivcdMZK+aY70lpe/dSBn3ge0pDRUIl543r0tdApcdlVzzdk7R+lIzQen+yL' +
        'WuwiDxnkrKg2Nne5w2/T4QABJuQ9BZ9sbu5CRrX+V5dgA4zyoOv3zP3GYczYvJEY5+wLnFbeJdOGwbbFGwIDAQAB'
    const CONTRACT_FINGERPRINT = '104p6RV0KuYaIUCTWE5Hy8fTY-iykPYhbjq-9NTGSyw'

    it('derives the same fingerprint as the server for the shared vector', async () => {
        expect(await fingerprint(CONTRACT_SPKI)).toBe(CONTRACT_FINGERPRINT)
    })

    it('gives different keys different ids', async () => {
        const a = await exportPublicKey((await generateKeyPair()).publicKey)
        const b = await exportPublicKey((await generateKeyPair()).publicKey)
        expect(await fingerprint(a)).not.toBe(await fingerprint(b))
    })

    it('answers a challenge with HMAC(secret, context || nonce), which only the private key can produce', async () => {
        const me = await generateKeyPair()
        const other = await generateKeyPair()
        const { secret, wrappedKey } = await sealSecretTo(me.publicKey)
        const nonce = crypto.getRandomValues(new Uint8Array(32))

        const mac = await answerChallenge(wrappedKey, b64(nonce), me.privateKey)

        const context = new TextEncoder().encode('eeck-handshake-v1')
        const expected = await crypto.subtle.sign(
            'HMAC',
            await crypto.subtle.importKey('raw', secret, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']),
            new Uint8Array([...context, ...nonce]),
        )
        expect(mac).toBe(b64(new Uint8Array(expected)))

        await expect(answerChallenge(wrappedKey, b64(nonce), other.privateKey)).rejects.toThrow()
    })

    it('cannot be turned into an AES encryption oracle for a peer-wrapped key', async () => {
        // A malicious server could put a peer's wrapped content key in a challenge.
        // The handshake must only ever produce an HMAC over it — never an AES-GCM
        // ciphertext, which would let the server forge messages under that key.
        const me = await generateKeyPair()
        const peerContentKey = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt'])
        const wrapped = b64(new Uint8Array(await crypto.subtle.wrapKey('raw', peerContentKey, me.publicKey, { name: 'RSA-OAEP' })))

        const output = await answerChallenge(wrapped, b64(new Uint8Array(32)), me.privateKey)

        // 32 bytes of MAC — no IV, no tag, nothing that decrypts under the content key.
        expect(base64ToBytes(output).byteLength).toBe(32)
    })

    async function sealSecretTo(publicKey: CryptoKey): Promise<{ secret: Uint8Array<ArrayBuffer>; wrappedKey: string }> {
        const secret = crypto.getRandomValues(new Uint8Array(32))
        const key = await crypto.subtle.importKey('raw', secret, { name: 'HMAC', hash: 'SHA-256' }, true, ['sign'])
        const wrapped = await crypto.subtle.wrapKey('raw', key, publicKey, { name: 'RSA-OAEP' })
        return { secret, wrappedKey: b64(new Uint8Array(wrapped)) }
    }

    function b64(bytes: Uint8Array): string {
        return bytesToBase64(bytes)
    }
})
