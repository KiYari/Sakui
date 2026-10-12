import { checkAccess, checkHealth, type ServerEndpoint } from '../../../shared/api'

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
    if (!endpoint.key) return { status: 'key-needed', error: 'This server needs an access key.' }
    try {
        await checkAccess(endpoint)
        return { status: 'ok', keyRequired: true }
    } catch (err) {
        return { status: 'key-needed', error: message(err, 'That access key was not accepted.') }
    }
}
