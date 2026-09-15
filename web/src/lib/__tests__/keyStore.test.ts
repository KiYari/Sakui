import { describe, expect, it } from 'vitest'
import { getOrCreateKeyPair } from '../keyStore'

describe('keyStore', () => {
    it('generates a keypair on first use and persists it across calls for the same chatId', async () => {
        const chatId = `chat-${crypto.randomUUID()}`

        const first = await getOrCreateKeyPair(chatId)
        const second = await getOrCreateKeyPair(chatId)

        const firstSpki = await crypto.subtle.exportKey('spki', first.publicKey)
        const secondSpki = await crypto.subtle.exportKey('spki', second.publicKey)
        expect(new Uint8Array(firstSpki)).toEqual(new Uint8Array(secondSpki))
    })

    it('generates distinct keypairs for different chatIds', async () => {
        const a = await getOrCreateKeyPair(`chat-${crypto.randomUUID()}`)
        const b = await getOrCreateKeyPair(`chat-${crypto.randomUUID()}`)

        const aSpki = await crypto.subtle.exportKey('spki', a.publicKey)
        const bSpki = await crypto.subtle.exportKey('spki', b.publicKey)
        expect(new Uint8Array(aSpki)).not.toEqual(new Uint8Array(bSpki))
    })

    it('returns a private key that is still non-extractable after a round trip through storage', async () => {
        const chatId = `chat-${crypto.randomUUID()}`
        await getOrCreateKeyPair(chatId)

        const reloaded = await getOrCreateKeyPair(chatId)

        expect(reloaded.privateKey.extractable).toBe(false)
        await expect(crypto.subtle.exportKey('pkcs8', reloaded.privateKey)).rejects.toThrow()
    })
})
