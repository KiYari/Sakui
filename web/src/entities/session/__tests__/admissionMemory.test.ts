import { describe, expect, it } from 'vitest'
import { memoryStorage } from '../../../shared/lib/local-store'
import { ADMISSION_MEMORY_MAX_AGE_MS, createAdmissionMemory, forgetAdmissionMemory } from '../model/admissionMemory'

describe('admission memory', () => {
    it('remembers decisions per chat, and survives a new instance (a reload)', () => {
        const storage = memoryStorage()
        const first = createAdmissionMemory('chat-a', storage)
        first.remember('peer-1', 'approved')
        first.remember('peer-2', 'rejected')

        const reloaded = createAdmissionMemory('chat-a', storage)
        expect(reloaded.decisionFor('peer-1')).toBe('approved')
        expect(reloaded.decisionFor('peer-2')).toBe('rejected')
        expect(reloaded.decisionFor('peer-3')).toBeNull()

        // A decision in one chat says nothing about another.
        expect(createAdmissionMemory('chat-b', storage).decisionFor('peer-1')).toBeNull()
    })

    it('lets a later decision replace an earlier one', () => {
        const memory = createAdmissionMemory('chat-a', memoryStorage())
        memory.remember('peer-1', 'rejected')
        memory.remember('peer-1', 'approved')

        expect(memory.decisionFor('peer-1')).toBe('approved')
    })

    it('expires old decisions and prunes chats left with none', () => {
        const storage = memoryStorage()
        let now = 1_000_000
        createAdmissionMemory('old-chat', storage, () => now).remember('peer-1', 'approved')
        expect(storage.length).toBe(1)

        now += ADMISSION_MEMORY_MAX_AGE_MS + 1
        const memory = createAdmissionMemory('new-chat', storage, () => now)

        expect(storage.length).toBe(0)
        expect(memory.decisionFor('peer-1')).toBeNull()
    })

    it('ignores corrupt stored data rather than trusting it', () => {
        const storage = memoryStorage()
        storage.setItem('eeck-admission:chat-a', JSON.stringify({ 'peer-1': { decision: 'owner', at: Date.now() } }))

        expect(createAdmissionMemory('chat-a', storage).decisionFor('peer-1')).toBeNull()
    })

    it('can be forgotten when its chat is deleted', () => {
        const storage = memoryStorage()
        createAdmissionMemory('chat-a', storage).remember('peer-1', 'approved')

        forgetAdmissionMemory('chat-a', storage)

        expect(storage.length).toBe(0)
    })

    it('works — by forgetting — when storage is unavailable', () => {
        const memory = createAdmissionMemory('chat-a', undefined)
        memory.remember('peer-1', 'approved')

        expect(memory.decisionFor('peer-1')).toBeNull()
    })
})
