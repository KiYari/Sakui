import { describe, expect, it } from 'vitest'
import { ChatSession } from '../chatSession'

/**
 * Regression test for a real bug: React StrictMode's dev-only
 * mount -> cleanup -> remount runs the effect cleanup (dispose()) while the
 * first start() is still awaiting the async keypair lookup, i.e. before
 * client.connect() has ever been called. Without a disposed-guard, that
 * "stale" session resumes after the await and opens a real WebSocket
 * anyway, leaving a ghost connection occupying a room slot.
 *
 * These tests run against the real local server (must be running on
 * localhost:3001) so they exercise the actual room-capacity behavior, not a
 * mock of it.
 */
describe('ChatSession dispose-during-start race', () => {
    it('a session disposed before start() finishes its first await never connects', async () => {
        const createRes = await fetch('http://localhost:3001/api/chat-link', { method: 'POST' })
        const { hash: chatId } = (await createRes.json()) as { hash: string }

        const statusEvents: string[] = []
        const session = new ChatSession(chatId, 'ghost-user', {
            onMessage: () => {},
            onPeerOnlineChange: () => {},
            onKeysExchangedChange: () => {},
            onConnectionStatusChange: (s) => statusEvents.push(s),
        })

        const startPromise = session.start()
        session.dispose() // synchronous - lands before the internal `await getOrCreateKeyPair(...)` resolves
        await startPromise

        expect(statusEvents).not.toContain('open')
        expect(statusEvents).not.toContain('connecting')

        // Confirm no ghost participant is occupying a room slot: a real
        // connection to the same chatId should see an empty room (no
        // participant-joined-style frame waiting for it).
        const ws = new WebSocket(`ws://localhost:3001/ws?chatId=${chatId}&userId=real-user`)
        await new Promise<void>((resolve, reject) => {
            ws.addEventListener('open', () => resolve(), { once: true })
            ws.addEventListener('error', () => reject(new Error('ws connect failed')), { once: true })
        })

        let sawUnexpectedFrame = false
        ws.addEventListener('message', () => {
            sawUnexpectedFrame = true
        })
        await new Promise((resolve) => setTimeout(resolve, 300))
        ws.close()

        expect(sawUnexpectedFrame).toBe(false)
    })
})
