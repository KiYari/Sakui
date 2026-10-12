import { afterEach, describe, expect, it, vi } from 'vitest'
import { authHeaders, socketUrl } from '../serverEndpoint'

describe('access key on the wire', () => {
    afterEach(() => vi.unstubAllGlobals())

    it('sends nothing when there is no key', () => {
        expect(authHeaders({ base: '' })).toEqual({})
    })

    it('leaves a plain ASCII key unchanged in the header', () => {
        expect(authHeaders({ base: '', key: 'SECRET' })).toEqual({ 'X-Eeck-Key': 'SECRET' })
    })

    it('percent-encodes characters a header cannot carry', () => {
        const value = authHeaders({ base: '', key: 'пароль 1%' })['X-Eeck-Key']
        expect(value).toMatch(/^[\x20-\x7e]+$/)
        expect(decodeURIComponent(value)).toBe('пароль 1%')
    })

    it('puts the key in the socket URL query, where the framework decodes it', () => {
        // The suite runs without a DOM; socketUrl only reads the page origin.
        vi.stubGlobal('window', { location: { origin: 'https://app.example' } })
        const url = socketUrl({ base: 'https://chat.example', key: 'пароль' }, '/ws')
        expect(url.searchParams.get('key')).toBe('пароль')
        expect(url.protocol).toBe('wss:')
    })
})
