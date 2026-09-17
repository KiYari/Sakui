import { describe, expect, it } from 'vitest'
import { memoryStorage } from '../../local-store'
import { forgetOwnerToken, ownerTokenFor, rememberOwnerToken } from '../ownerTokens'

const DAY_MS = 24 * 60 * 60 * 1000

describe('owner tokens', () => {
    it('are kept per chat and can be forgotten', () => {
        const storage = memoryStorage()
        rememberOwnerToken('chat-a', 'token-a', storage)
        rememberOwnerToken('chat-b', 'token-b', storage)

        expect(ownerTokenFor('chat-a', storage)).toBe('token-a')
        expect(ownerTokenFor('chat-c', storage)).toBeNull()

        forgetOwnerToken('chat-a', storage)
        expect(ownerTokenFor('chat-a', storage)).toBeNull()
        expect(ownerTokenFor('chat-b', storage)).toBe('token-b')
    })

    it('stop counting once their link has certainly expired, and are pruned on the next save', () => {
        const storage = memoryStorage()
        const created = 1_000_000
        rememberOwnerToken('old', 'token', storage, created)

        expect(ownerTokenFor('old', storage, created + DAY_MS)).toBe('token')
        expect(ownerTokenFor('old', storage, created + 2 * DAY_MS)).toBeNull()

        rememberOwnerToken('new', 'token', storage, created + 2 * DAY_MS)
        expect(storage.length).toBe(1)
    })
})
