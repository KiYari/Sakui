import { describe, expect, it } from 'vitest'
import { ChatSession } from '../model/ChatSession'

/**
 * Regression test for a real bug: React StrictMode's dev-only
 * mount -> cleanup -> remount runs the effect cleanup (dispose()) while the
 * first start() is still awaiting the async keypair lookup, i.e. before
 * client.connect() has ever been called. Without a disposed-guard, that
 * "stale" session resumes after the await and opens a real WebSocket
 * anyway, leaving a ghost connection occupying the room.
 *
 * Runs against the real local server (must be running on localhost:3001) so
 * it exercises actual join behaviour rather than a mock of it.
 */
// Overridable so the test can target a second backend without disturbing one already on :3001.
const API = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env.EECK_API_URL ?? 'http://localhost:3001'

describe('ChatSession dispose-during-start race', () => {
    it('a session disposed before start() finishes its first await never connects', async () => {
        const createRes = await fetch(`${API}/api/chat-link`, { method: 'POST' })
        const { hash: chatId } = (await createRes.json()) as { hash: string }

        const session = new ChatSession(chatId)

        // The store is the observable surface now: a session that never connects
        // must never push an update to its subscribers.
        let notifications = 0
        session.subscribe(() => {
            notifications += 1
        })
        const before = session.getSnapshot()

        const startPromise = session.start()
        session.dispose() // synchronous - lands before the internal keypair await resolves
        await startPromise

        expect(notifications).toBe(0)
        expect(session.getSnapshot()).toBe(before)

        // Confirm no ghost participant is occupying the room: a real connection to
        // the same chatId should see an empty room (no participant-joined frame
        // waiting for it).
        const ws = new WebSocket(`${API.replace(/^http/, 'ws')}/ws?chatId=${chatId}`)
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
