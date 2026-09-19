import { describe, expect, it } from 'vitest'
import { memoryStorage } from '../../local-store'
import { draftFor, forgetDraft, rememberDraft } from '../messageDrafts'

describe('message drafts', () => {
    it('are kept per chat and can be forgotten', () => {
        const storage = memoryStorage()
        rememberDraft('chat-a', 'hello', storage)
        rememberDraft('chat-b', 'world', storage)

        expect(draftFor('chat-a', storage)).toBe('hello')
        expect(draftFor('chat-c', storage)).toBe('')

        forgetDraft('chat-a', storage)
        expect(draftFor('chat-a', storage)).toBe('')
        expect(draftFor('chat-b', storage)).toBe('world')
    })

    it('clears itself once the text is emptied out', () => {
        const storage = memoryStorage()
        rememberDraft('chat-a', 'hello', storage)
        rememberDraft('chat-a', '', storage)

        expect(draftFor('chat-a', storage)).toBe('')
        expect(storage.length).toBe(0)
    })
})
