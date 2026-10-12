/**
 * Where the chat server is, as seen from this page. The whole client reaches
 * the server through one string, so every deployment shape fits it:
 *
 * - `''` — same origin: the page and the server share a host (nginx in front,
 *   or the single-process release).
 * - `'/s/1.2.3.4:3001'` — through the relay on this page's own origin, which
 *   forwards `/api` and `/ws` to that address (see `web/relay.conf`).
 * - `'https://host'` — directly to another origin, which then has to allow
 *   this page's origin in its CORS settings.
 *
 * A server may also require an access key (`EECK_ACCESS_KEY` on the server),
 * which then rides on every request: see [authHeaders] and [socketUrl].
 */
export interface ServerEndpoint {
    readonly base: string
    readonly key?: string
}

/** Must match the server's `ACCESS_KEY_HEADER`. */
const ACCESS_KEY_HEADER = 'X-Eeck-Key'

export const SAME_ORIGIN: ServerEndpoint = { base: '' }

export function httpUrl(endpoint: ServerEndpoint, path: string): string {
    return endpoint.base + path
}

/** Headers a REST call to this server must carry. */
export function authHeaders(endpoint: ServerEndpoint): Record<string, string> {
    // Header values must be Latin-1, so a key with any other character (Cyrillic, say) would make
    // fetch throw. Percent-encoding is undone by the server; plain ASCII keys pass through unchanged.
    return endpoint.key ? { [ACCESS_KEY_HEADER]: encodeURIComponent(endpoint.key) } : {}
}

export function socketUrl(endpoint: ServerEndpoint, path: string): URL {
    // An absolute base ignores the page origin; a relative one resolves against it.
    const url = new URL(endpoint.base + path, window.location.origin)
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    // A WebSocket handshake cannot carry custom headers, so the key goes in the query.
    if (endpoint.key) url.searchParams.set('key', endpoint.key)
    return url
}
