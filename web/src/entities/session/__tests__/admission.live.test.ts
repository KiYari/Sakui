import { afterEach, describe, expect, it } from 'vitest'
import { memoryStorage } from '../../../shared/lib/local-store'
import { generateKeyPair, type ChatKeyPair } from '../../../shared/lib/crypto'
import { createAdmissionMemory, type AdmissionMemory } from '../model/admissionMemory'
import { ChatSession, type SessionSnapshot } from '../model/ChatSession'
import { canSend } from '../model/connection'
import type { Profile } from '../model/profile'

/**
 * The admission flow end to end: real sessions, real Web Crypto, the real
 * server (on localhost:3001 unless EECK_API_URL says otherwise).
 */
const API = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env.EECK_API_URL ?? 'http://localhost:3001'

// E2eeClient derives the socket URL from the page origin; under node there is no page.
;(globalThis as { window?: unknown }).window ??= { location: { origin: API } }

const sessions: ChatSession[] = []

afterEach(() => {
    sessions.splice(0).forEach((session) => session.dispose())
})

async function newChat(): Promise<string> {
    const res = await fetch(`${API}/api/chat-link`, { method: 'POST' })
    // 429 here means the server's per-client link budget (10/min) is spent by earlier runs.
    if (!res.ok) throw new Error(`creating a link failed: ${res.status}`)
    return ((await res.json()) as { hash: string }).hash
}

function startSession(chatId: string, keys: ChatKeyPair, profile: Profile, admissionMemory?: AdmissionMemory): ChatSession {
    const session = new ChatSession(chatId, { loadKeyPair: () => Promise.resolve(keys), profile, admissionMemory })
    sessions.push(session)
    void session.start()
    return session
}

async function until(session: ChatSession, condition: (s: SessionSnapshot) => boolean, what: string): Promise<SessionSnapshot> {
    const deadline = Date.now() + 5_000
    while (!condition(session.getSnapshot())) {
        if (Date.now() > deadline) throw new Error(`timed out waiting for: ${what}`)
        await new Promise((resolve) => setTimeout(resolve, 20))
    }
    return session.getSnapshot()
}

describe('admission (live server)', () => {
    it('the host sees who is waiting, lets them in, and both can talk', async () => {
        const chatId = await newChat()
        const host = startSession(chatId, await generateKeyPair(), { name: 'Alice', color: 1 }, createAdmissionMemory(chatId, memoryStorage()))
        const hostSnap = await until(host, (s) => s.connection.status === 'waiting' && s.hostId === s.selfId, 'host admitted')

        const guest = startSession(chatId, await generateKeyPair(), { name: 'Bob', color: 2 })
        const guestSnap = await until(guest, (s) => s.connection.status === 'pending', 'guest pending')
        const guestId = guestSnap.selfId!

        // At the door, keys and profiles cross — nothing else.
        await until(host, (s) => s.joinRequests.some((r) => r.peerId === guestId) && s.profiles[guestId]?.name === 'Bob', 'host sees Bob')
        await until(guest, (s) => s.profiles[hostSnap.selfId!]?.name === 'Alice', 'guest sees Alice')
        expect(canSend(guest.getSnapshot().connection)).toBe(false)

        host.admit(guestId)

        await until(guest, (s) => canSend(s.connection), 'guest can send')
        await until(host, (s) => canSend(s.connection) && s.joinRequests.length === 0, 'host can send')
        await until(
            host,
            (s) => s.messages.some((m) => m.kind === 'presence' && m.event === 'joined' && m.peerId === guestId),
            'join notice',
        )

        await guest.sendMessage('hello from Bob')
        await until(host, (s) => s.messages.some((m) => m.kind === 'text' && m.text === 'hello from Bob' && m.sender === guestId), 'message')
    })

    it('a remembered approval lets the same key back in without asking again', async () => {
        const chatId = await newChat()
        const memory = createAdmissionMemory(chatId, memoryStorage())
        const host = startSession(chatId, await generateKeyPair(), { name: 'Alice', color: 1 }, memory)
        await until(host, (s) => s.connection.status === 'waiting', 'host admitted')

        const guestKeys = await generateKeyPair()
        const guest = startSession(chatId, guestKeys, { name: 'Bob', color: 2 })
        const guestId = (await until(guest, (s) => s.connection.status === 'pending', 'guest pending')).selfId!
        await until(host, (s) => s.joinRequests.length === 1, 'request shown')
        host.admit(guestId)
        await until(guest, (s) => canSend(s.connection), 'guest in')

        guest.dispose()
        await until(host, (s) => s.messages.some((m) => m.kind === 'presence' && m.event === 'left'), 'guest left')

        const returning = startSession(chatId, guestKeys, { name: 'Bob', color: 2 })
        await until(returning, (s) => canSend(s.connection), 'returning guest let straight in')
        expect(host.getSnapshot().joinRequests).toEqual([])
    })

    it('a declined joiner is turned away and told why', async () => {
        const chatId = await newChat()
        const host = startSession(chatId, await generateKeyPair(), { name: 'Alice', color: 1 }, createAdmissionMemory(chatId, memoryStorage()))
        await until(host, (s) => s.connection.status === 'waiting', 'host admitted')

        const guest = startSession(chatId, await generateKeyPair(), { name: 'Mallory', color: 4 })
        const guestId = (await until(guest, (s) => s.connection.status === 'pending', 'guest pending')).selfId!
        await until(host, (s) => s.joinRequests.length === 1, 'request shown')

        host.reject(guestId)

        const ended = await until(guest, (s) => s.connection.status === 'terminated', 'guest terminated')
        expect(ended.connection).toMatchObject({ status: 'terminated', code: 'REJECTED' })
    })
})
