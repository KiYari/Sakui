import type { ServerEndpoint } from '../../api'
import { browserStorage, readJson, writeJson, type KeyValueStorage } from '../local-store'

/**
 * How this build of the client reaches a chat server, fixed at build time by
 * `VITE_EECK_SERVER_MODE`:
 *
 * - `same-origin` (default) — one server, on the page's own origin. No picker.
 * - `relay` — the user picks a server by IPv4 address; the web host forwards
 *   to it (`web/relay.conf`), so that server can stay on plain HTTP.
 * - `direct` — the user picks a server and the browser connects to it itself.
 *   It must serve HTTPS (an HTTPS page may not open plain sockets), so a bare
 *   IP is reached through its sslip.io name, which resolves back to that IP
 *   and lets the server's Caddy obtain a certificate with no domain bought.
 */
export type ServerMode = 'same-origin' | 'relay' | 'direct'

export function parseServerMode(raw: string | undefined): ServerMode {
    return raw === 'relay' || raw === 'direct' ? raw : 'same-origin'
}

export const SERVER_MODE: ServerMode = parseServerMode(import.meta.env.VITE_EECK_SERVER_MODE)

/** The port `./gradlew :server:run` and the release's run.sh listen on. */
export const DEFAULT_RELAY_PORT = 3001

export type ParsedServer = { ok: true; server: string } | { ok: false; error: string }

const OCTET = '(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)'
const IPV4 = new RegExp(`^${OCTET}(?:\\.${OCTET}){3}$`)
const LABEL = '[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?'
const HOSTNAME = new RegExp(`^${LABEL}(?:\\.${LABEL})*$`)
const ADDRESS = /^(?:(https?):\/\/)?([^/:]+)(?::(\d{1,5}))?\/?$/

/**
 * Turns what a user typed (or a link's `?server=`) into the canonical server
 * string that goes back into links: `1.2.3.4:3001` for the relay;
 * `host`, `host:port` or `http://host:port` for direct connections.
 */
export function parseServer(input: string, mode: ServerMode): ParsedServer {
    const match = ADDRESS.exec(input.trim().toLowerCase())
    if (!match) return { ok: false, error: 'Enter an address like 203.0.113.7 or 203.0.113.7:3001.' }
    const [, scheme, host, portText] = match
    const port = portText === undefined ? undefined : Number(portText)
    if (port !== undefined && (port < 1 || port > 65535)) return { ok: false, error: 'The port must be between 1 and 65535.' }

    if (mode === 'relay') {
        if (scheme) return { ok: false, error: 'Leave out http:// — enter just the IP address and port.' }
        if (!IPV4.test(host)) return { ok: false, error: 'Enter the server’s IPv4 address, like 203.0.113.7.' }
        // The relay refuses these too; saying so here beats a bare 404.
        if (isNonPublicIpv4(host)) return { ok: false, error: 'That is a private or local address, which the relay will not forward to.' }
        return { ok: true, server: `${host}:${port ?? DEFAULT_RELAY_PORT}` }
    }

    if (!IPV4.test(host) && !HOSTNAME.test(host)) return { ok: false, error: 'That is not a valid host name or IP address.' }
    const authority = port === undefined ? host : `${host}:${port}`
    // https is the default and stays implicit; plain http is kept only when asked for (local development).
    return { ok: true, server: scheme === 'http' ? `http://${authority}` : authority }
}

/** Where to send traffic for a server string produced by [parseServer]. */
export function endpointFor(server: string, mode: ServerMode): ServerEndpoint {
    switch (mode) {
        case 'same-origin':
            return { base: '' }
        case 'relay':
            return { base: `/s/${server}` }
        case 'direct': {
            if (server.startsWith('http://')) return { base: server }
            const [host, port] = server.split(':')
            // A page may open plain http/ws to loopback even when it is itself served over https.
            if (host === 'localhost' || host === '127.0.0.1') return { base: `http://${server}` }
            const name = IPV4.test(host) ? `${host.replaceAll('.', '-')}.sslip.io` : host
            return { base: `https://${name}${port ? `:${port}` : ''}` }
        }
    }
}

/**
 * Loopback, private, link-local, CGNAT, multicast and reserved ranges. Kept in
 * step with the `$eeck_relay_blocked` map in `web/nginx.conf`, which is what
 * actually enforces it.
 */
export function isNonPublicIpv4(ip: string): boolean {
    const [a, b] = ip.split('.').map(Number)
    return (
        a === 0 ||
        a === 10 ||
        a === 127 ||
        (a === 100 && b >= 64 && b <= 127) ||
        (a === 169 && b === 254) ||
        (a === 172 && b >= 16 && b <= 31) ||
        (a === 192 && b === 168) ||
        a >= 224
    )
}

const RECENT_KEY = 'eeck-recent-servers'
const MAX_RECENT = 5

export function recentServers(storage: KeyValueStorage | undefined = browserStorage()): string[] {
    const stored = readJson(storage, RECENT_KEY)
    return Array.isArray(stored) ? stored.filter((s): s is string => typeof s === 'string').slice(0, MAX_RECENT) : []
}

/** Moves [server] to the front of the recent list. */
export function rememberServer(server: string, storage: KeyValueStorage | undefined = browserStorage()): void {
    const next = [server, ...recentServers(storage).filter((s) => s !== server)].slice(0, MAX_RECENT)
    writeJson(storage, RECENT_KEY, next)
}

export function forgetServer(server: string, storage: KeyValueStorage | undefined = browserStorage()): void {
    forgetKey(server, storage)
    writeJson(
        storage,
        RECENT_KEY,
        recentServers(storage).filter((s) => s !== server),
    )
}

/** What [parseServer] yields for a same-origin build, which has no address to enter. */
export const SAME_ORIGIN_SERVER = ''

const KEYS_KEY = 'eeck-server-keys'

function readKeys(storage: KeyValueStorage | undefined): Record<string, string> {
    const stored = readJson(storage, KEYS_KEY)
    if (typeof stored !== 'object' || stored === null || Array.isArray(stored)) return {}
    return Object.fromEntries(Object.entries(stored).filter((e): e is [string, string] => typeof e[1] === 'string'))
}

/**
 * The access key last accepted by [server], if any. Kept in this browser only
 * and never put in a link: the link is shared, the key is the secret that
 * decides who may use the server at all.
 */
export function keyFor(server: string, storage: KeyValueStorage | undefined = browserStorage()): string | undefined {
    return readKeys(storage)[server] || undefined
}

export function rememberKey(server: string, key: string, storage: KeyValueStorage | undefined = browserStorage()): void {
    writeJson(storage, KEYS_KEY, { ...readKeys(storage), [server]: key })
}

export function forgetKey(server: string, storage: KeyValueStorage | undefined = browserStorage()): void {
    const { [server]: _dropped, ...rest } = readKeys(storage)
    writeJson(storage, KEYS_KEY, rest)
}
