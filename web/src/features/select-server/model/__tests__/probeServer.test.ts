import { afterEach, describe, expect, it, vi } from 'vitest'
import { onAccessDenied, reportAccessDenied } from '../../../../shared/api'
import { probeServer } from '../probeServer'

/** Answers each path the way an eeck server with the given key setting would. */
function fakeServer(options: { keyRequired: boolean; acceptedKey?: string }) {
    const fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
        const key = (init?.headers as Record<string, string> | undefined)?.['X-Eeck-Key']
        if (input.endsWith('/api/health')) {
            return Response.json({ status: 'ok', keyRequired: options.keyRequired })
        }
        if (input.endsWith('/api/access')) {
            if (!key) return Response.json({ error: 'This server needs an access key.' }, { status: 401 })
            if (key !== options.acceptedKey) return Response.json({ error: 'Wrong access key.' }, { status: 401 })
            return Response.json({ status: 'ok', keyRequired: true })
        }
        return new Response('nope', { status: 404 })
    })
    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
}

afterEach(() => {
    vi.unstubAllGlobals()
})

describe('probeServer', () => {
    it('accepts an open server and ignores any key', async () => {
        fakeServer({ keyRequired: false })
        expect(await probeServer({ base: '/s/1.2.3.4:3001', key: 'unneeded' })).toEqual({ status: 'ok', keyRequired: false })
    })

    it('asks for a key when the server wants one and none was given', async () => {
        fakeServer({ keyRequired: true, acceptedKey: 'secret' })
        expect(await probeServer({ base: '' })).toEqual({ status: 'key-needed', error: 'This server needs an access key.' })
    })

    it('reports the server’s own reason for a wrong key', async () => {
        fakeServer({ keyRequired: true, acceptedKey: 'secret' })
        expect(await probeServer({ base: '', key: 'old' })).toEqual({ status: 'key-needed', error: 'Wrong access key.' })
    })

    it('accepts the right key', async () => {
        fakeServer({ keyRequired: true, acceptedKey: 'secret' })
        expect(await probeServer({ base: '', key: 'secret' })).toEqual({ status: 'ok', keyRequired: true })
    })

    it('tells an unreachable server from a key problem', async () => {
        vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network down')))
        expect((await probeServer({ base: '' })).status).toBe('unreachable')
    })

    it('does not mistake some other web server for an eeck server', async () => {
        vi.stubGlobal('fetch', vi.fn(async () => new Response('<html></html>', { status: 200 })))
        expect((await probeServer({ base: '' })).status).toBe('unreachable')
    })
})

describe('access denied notifications', () => {
    it('reach every listener until it unsubscribes', () => {
        const seen: string[] = []
        const stop = onAccessDenied((m) => seen.push(m))
        reportAccessDenied('first')
        stop()
        reportAccessDenied('second')
        expect(seen).toEqual(['first'])
    })
})
