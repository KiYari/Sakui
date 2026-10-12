import { describe, expect, it } from 'vitest'
import { memoryStorage } from '../../local-store'
import { endpointFor, forgetKey, forgetServer, keyFor, rememberKey, isNonPublicIpv4, parseServer, parseServerMode, recentServers, rememberServer } from '../serverTarget'

function server(input: string, mode: 'relay' | 'direct'): string {
    const parsed = parseServer(input, mode)
    if (!parsed.ok) throw new Error(parsed.error)
    return parsed.server
}

describe('parseServerMode', () => {
    it('falls back to same-origin for anything unknown', () => {
        expect(parseServerMode(undefined)).toBe('same-origin')
        expect(parseServerMode('RELAY')).toBe('same-origin')
        expect(parseServerMode('relay')).toBe('relay')
        expect(parseServerMode('direct')).toBe('direct')
    })
})

describe('relay mode', () => {
    it('accepts a public IPv4 address and fills in the default port', () => {
        expect(server(' 203.0.113.7 ', 'relay')).toBe('203.0.113.7:3001')
        expect(server('203.0.113.7:80', 'relay')).toBe('203.0.113.7:80')
    })

    it('rejects host names, schemes, paths, bad ports and private addresses', () => {
        const rejected = [
            'example.com',
            'http://203.0.113.7',
            '203.0.113.7/x',
            '203.0.113.7:0',
            '203.0.113.7:70000',
            '256.1.1.1',
            '10.0.0.1',
            '127.0.0.1:3001',
            '192.168.1.5',
            '172.20.0.2',
            '169.254.169.254',
            '',
        ]
        for (const input of rejected) expect(parseServer(input, 'relay').ok, input).toBe(false)
    })

    it('routes through the relay prefix on this origin', () => {
        expect(endpointFor('203.0.113.7:3001', 'relay')).toEqual({ base: '/s/203.0.113.7:3001' })
    })
})

describe('direct mode', () => {
    it('keeps host names and ports, drops an explicit https', () => {
        expect(server('Chat.Example.com', 'direct')).toBe('chat.example.com')
        expect(server('https://chat.example.com:8443/', 'direct')).toBe('chat.example.com:8443')
        expect(server('http://localhost:3001', 'direct')).toBe('http://localhost:3001')
    })

    it('rejects paths and junk', () => {
        for (const input of ['chat.example.com/evil', 'ftp://x', 'a b', '-bad.com', '']) {
            expect(parseServer(input, 'direct').ok, input).toBe(false)
        }
    })

    it('reaches a bare IP through its sslip.io name over HTTPS', () => {
        expect(endpointFor('203.0.113.7', 'direct')).toEqual({ base: 'https://203-0-113-7.sslip.io' })
        expect(endpointFor('203.0.113.7:8443', 'direct')).toEqual({ base: 'https://203-0-113-7.sslip.io:8443' })
        expect(endpointFor('chat.example.com', 'direct')).toEqual({ base: 'https://chat.example.com' })
        expect(endpointFor('http://localhost:3001', 'direct')).toEqual({ base: 'http://localhost:3001' })
    })
})

describe('isNonPublicIpv4', () => {
    it('separates public from reserved ranges', () => {
        expect(isNonPublicIpv4('8.8.8.8')).toBe(false)
        expect(isNonPublicIpv4('172.32.0.1')).toBe(false)
        expect(isNonPublicIpv4('100.63.0.1')).toBe(false)
        expect(isNonPublicIpv4('100.64.0.1')).toBe(true)
        expect(isNonPublicIpv4('0.0.0.0')).toBe(true)
        expect(isNonPublicIpv4('224.0.0.1')).toBe(true)
    })
})

describe('recent servers', () => {
    it('keeps the five most recent, newest first, without duplicates', () => {
        const storage = memoryStorage()
        for (const s of ['a', 'b', 'c', 'd', 'e', 'f', 'b']) rememberServer(s, storage)
        expect(recentServers(storage)).toEqual(['b', 'f', 'e', 'd', 'c'])
        forgetServer('f', storage)
        expect(recentServers(storage)).toEqual(['b', 'e', 'd', 'c'])
    })

    it('ignores corrupted storage', () => {
        const storage = memoryStorage()
        storage.setItem('eeck-recent-servers', '{"not":"a list"}')
        expect(recentServers(storage)).toEqual([])
    })
})

describe('access keys', () => {
    it('are remembered per server and forgotten with it', () => {
        const storage = memoryStorage()
        expect(keyFor('1.2.3.4:3001', storage)).toBeUndefined()
        rememberKey('1.2.3.4:3001', 'secret', storage)
        rememberKey('5.6.7.8:3001', 'other', storage)
        expect(keyFor('1.2.3.4:3001', storage)).toBe('secret')
        forgetKey('1.2.3.4:3001', storage)
        expect(keyFor('1.2.3.4:3001', storage)).toBeUndefined()
        expect(keyFor('5.6.7.8:3001', storage)).toBe('other')

        forgetServer('5.6.7.8:3001', storage)
        expect(keyFor('5.6.7.8:3001', storage)).toBeUndefined()
    })

    it('ignore corrupted storage', () => {
        const storage = memoryStorage()
        storage.setItem('eeck-server-keys', '["not","a map"]')
        expect(keyFor('x', storage)).toBeUndefined()
        rememberKey('x', 'k', storage)
        expect(keyFor('x', storage)).toBe('k')
    })
})
