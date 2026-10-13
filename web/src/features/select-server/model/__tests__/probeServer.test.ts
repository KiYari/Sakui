import { afterEach, describe, expect, it, vi } from 'vitest'
import { onAccessDenied, reportAccessDenied } from '../../../../shared/api'
import { probeServer, signInToServer } from '../probeServer'

/** Answers each path the way an eeck server with the given key setting would. */
function fakeServer(options: { keyRequired: boolean; acceptedKey?: string; acceptedSecrets?: string[]; oneTime?: string[] }) {
    const fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
        const key = (init?.headers as Record<string, string> | undefined)?.['X-Eeck-Key']
        if (input.endsWith('/api/health')) {
            return Response.json({ status: 'ok', keyRequired: options.keyRequired })
        }
        if (input.endsWith('/api/auth/login')) {
            const { secret } = JSON.parse(String(init?.body)) as { secret: string }
            if (options.acceptedSecrets?.includes(secret)) {
                if (options.oneTime?.includes(secret)) options.acceptedSecrets.splice(options.acceptedSecrets.indexOf(secret), 1)
                return Response.json({ token: `token-for-${secret}`, method: 'password', expiresAtMillis: 0 })
            }
            return Response.json({ error: 'That password or code was not accepted.' }, { status: 401 })
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
        expect(await probeServer({ base: '' })).toEqual({ status: 'key-needed', error: 'This server needs a password or a one-time code.' })
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

describe('signInToServer', () => {
    it('lets you straight in when the server asks for nothing', async () => {
        fakeServer({ keyRequired: false })
        expect(await signInToServer({ base: '' }, {})).toEqual({ status: 'ok' })
    })

    it('trades the password for a session token', async () => {
        fakeServer({ keyRequired: true, acceptedSecrets: ['server-password'] })
        expect(await signInToServer({ base: '' }, { secret: ' server-password ' })).toEqual({
            status: 'ok',
            token: 'token-for-server-password',
        })
    })

    it('says what is needed when nothing was typed', async () => {
        fakeServer({ keyRequired: true, acceptedSecrets: [] })
        expect(await signInToServer({ base: '' }, {})).toEqual({
            status: 'needs-secret',
            error: 'This server needs a password or a one-time code.',
        })
    })

    it('reports a wrong password in the server’s words', async () => {
        fakeServer({ keyRequired: true, acceptedSecrets: ['right'] })
        expect(await signInToServer({ base: '' }, { secret: 'wrong' })).toEqual({
            status: 'needs-secret',
            error: 'That password or code was not accepted.',
        })
    })

    it('reuses a saved token that still works, without spending anything typed', async () => {
        const fetchMock = fakeServer({ keyRequired: true, acceptedKey: 'saved-token', acceptedSecrets: ['one-time'], oneTime: ['one-time'] })
        const result = await signInToServer({ base: '' }, { savedToken: 'saved-token', secret: 'one-time' })
        expect(result).toEqual({ status: 'ok', token: 'saved-token' })
        expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/api/auth/login'))).toBe(false)
    })

    it('falls back to what was typed when the saved token has lapsed', async () => {
        fakeServer({ keyRequired: true, acceptedKey: 'something-else', acceptedSecrets: ['server-password'] })
        const result = await signInToServer({ base: '' }, { savedToken: 'stale', secret: 'server-password' })
        expect(result).toEqual({ status: 'ok', token: 'token-for-server-password' })
    })

    it('tells you the session ended when the token lapsed and nothing was typed', async () => {
        fakeServer({ keyRequired: true, acceptedKey: 'something-else', acceptedSecrets: [] })
        const result = await signInToServer({ base: '' }, { savedToken: 'stale' })
        expect(result).toEqual({ status: 'needs-secret', error: 'Your session has ended. Enter the password or a one-time code.' })
    })

    it('a one-time code signs in once and is then refused', async () => {
        fakeServer({ keyRequired: true, acceptedSecrets: ['ABCDE-FGHJK'], oneTime: ['ABCDE-FGHJK'] })
        expect((await signInToServer({ base: '' }, { secret: 'ABCDE-FGHJK' })).status).toBe('ok')
        expect((await signInToServer({ base: '' }, { secret: 'ABCDE-FGHJK' })).status).toBe('needs-secret')
    })

    it('tells an unreachable server from a wrong secret', async () => {
        vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network down')))
        expect((await signInToServer({ base: '' }, { secret: 'x' })).status).toBe('unreachable')
    })
})
