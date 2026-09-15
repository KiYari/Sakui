import { describe, expect, it } from 'vitest'
import { decryptMessage, encryptMessage, exportPublicKey, generateKeyPair, importPublicKey } from '../crypto'

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
