import { checkAccess, checkHealth, login, type ServerEndpoint } from '../../../shared/api'

export type ProbeResult =
    /** [keyRequired] says whether the endpoint's key (if any) was actually needed. */
    | { status: 'ok'; keyRequired: boolean }
    /** The server is there but wants a key this endpoint lacks, or has the wrong one. */
    | { status: 'key-needed'; error: string }
    | { status: 'unreachable'; error: string }

const message = (err: unknown, fallback: string) => (err instanceof Error ? err.message : fallback)

/**
 * Finds out whether [endpoint] can be used as it is: reachable, an eeck server,
 * and — if it asks for an access key — one that it accepts. The picker uses it
 * before moving on, and the app uses it again when a link or a reload names a
 * server, so a missing or stale key always lands back on the key prompt.
 */
export async function probeServer(endpoint: ServerEndpoint): Promise<ProbeResult> {
    let keyRequired: boolean
    try {
        const health = await checkHealth(endpoint)
        keyRequired = health.keyRequired
    } catch (err) {
        return { status: 'unreachable', error: message(err, 'Could not reach that server.') }
    }
    if (!keyRequired) return { status: 'ok', keyRequired: false }
    if (!endpoint.key) return { status: 'key-needed', error: 'This server needs a password or a one-time code.' }
    try {
        await checkAccess(endpoint)
        return { status: 'ok', keyRequired: true }
    } catch (err) {
        return { status: 'key-needed', error: message(err, 'That access key was not accepted.') }
    }
}

export type SignInResult =
    /** [token] is what to use from now on; absent when the server asked for nothing. */
    | { status: 'ok'; token?: string }
    | { status: 'needs-secret'; error: string }
    | { status: 'unreachable'; error: string }

/**
 * Gets into [endpoint] as the user: a saved session token if it still works,
 * otherwise whatever they typed — the server password or a one-time code — traded
 * for a fresh token. The secret itself is never kept.
 */
export async function signInToServer(
    endpoint: ServerEndpoint,
    options: { secret?: string; savedToken?: string },
): Promise<SignInResult> {
    let keyRequired: boolean
    try {
        keyRequired = (await checkHealth(endpoint)).keyRequired
    } catch (err) {
        return { status: 'unreachable', error: message(err, 'Could not reach that server.') }
    }
    if (!keyRequired) return { status: 'ok' }

    if (options.savedToken) {
        try {
            await checkAccess({ ...endpoint, key: options.savedToken })
            return { status: 'ok', token: options.savedToken }
        } catch {
            // Expired, or the password changed: fall through to what was typed.
        }
    }
    const secret = options.secret?.trim()
    if (!secret) {
        const error = options.savedToken
            ? 'Your session has ended. Enter the password or a one-time code.'
            : 'This server needs a password or a one-time code.'
        return { status: 'needs-secret', error }
    }
    try {
        return { status: 'ok', token: (await login(endpoint, secret)).token }
    } catch (err) {
        return { status: 'needs-secret', error: message(err, 'That password or code was not accepted.') }
    }
}
