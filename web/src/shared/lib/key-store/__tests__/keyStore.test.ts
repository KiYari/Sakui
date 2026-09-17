import { describe, expect, it } from 'vitest'
import { deleteKeyPair, deleteKeyPairsForChat, getOrCreateKeyPair, KEY_MAX_AGE_MS } from '../keyStore'

async function spkiOf(pair: { publicKey: CryptoKey }): Promise<Uint8Array> {
    return new Uint8Array(await crypto.subtle.exportKey('spki', pair.publicKey))
}

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

    it("forgets one tab's key without touching another tab's key for the same chat", async () => {
        const chatId = `chat-${crypto.randomUUID()}`
        const tabA = await getOrCreateKeyPair(`${chatId}:tab-a`)
        const tabB = await getOrCreateKeyPair(`${chatId}:tab-b`)

        await deleteKeyPair(`${chatId}:tab-a`)

        expect(await spkiOf(await getOrCreateKeyPair(`${chatId}:tab-a`))).not.toEqual(await spkiOf(tabA))
        expect(await spkiOf(await getOrCreateKeyPair(`${chatId}:tab-b`))).toEqual(await spkiOf(tabB))
    })

    it("forgets every tab's key for a deleted chat, and only that chat", async () => {
        const chatId = `chat-${crypto.randomUUID()}`
        const other = `${chatId}-other:tab`
        const tabA = await getOrCreateKeyPair(`${chatId}:tab-a`)
        const tabB = await getOrCreateKeyPair(`${chatId}:tab-b`)
        const unrelated = await getOrCreateKeyPair(other)

        await deleteKeyPairsForChat(chatId)

        expect(await spkiOf(await getOrCreateKeyPair(`${chatId}:tab-a`))).not.toEqual(await spkiOf(tabA))
        expect(await spkiOf(await getOrCreateKeyPair(`${chatId}:tab-b`))).not.toEqual(await spkiOf(tabB))
        // A chat whose id merely starts with the same characters is a different chat.
        expect(await spkiOf(await getOrCreateKeyPair(other))).toEqual(await spkiOf(unrelated))
    })

    it('prunes keys old enough that their chat must have expired, but never the one being loaded', async () => {
        const start = Date.now()
        const stale = `chat-${crypto.randomUUID()}:tab`
        const active = `chat-${crypto.randomUUID()}:tab`
        const staleKey = await getOrCreateKeyPair(stale, start)
        const activeKey = await getOrCreateKeyPair(active, start)

        const later = start + KEY_MAX_AGE_MS + 1
        expect(await spkiOf(await getOrCreateKeyPair(active, later))).toEqual(await spkiOf(activeKey))
        expect(await spkiOf(await getOrCreateKeyPair(stale, later))).not.toEqual(await spkiOf(staleKey))
    })
})
