import { authHeaders, httpUrl, reportAccessDenied, type ServerEndpoint } from '../endpoint'

export interface LinkResponse {
    hash: string
    expired: boolean
    deleted: boolean
    /** Proves this browser created the link. Returned only once, here; required to delete it. */
    ownerToken: string
}

export interface StatusResponse {
    status: string
    state: string
}

interface ApiErrorBody {
    error?: string
}

async function parseJsonOrThrow<T>(res: Response): Promise<T> {
    // Error responses can come from a proxy or a plugin rather than the app, and
    // may carry no JSON at all; that must still read as a sensible message.
    let body: (T & ApiErrorBody) | undefined
    try {
        body = (await res.json()) as T & ApiErrorBody
    } catch {
        body = undefined
    }
    if (!res.ok) {
        // The server no longer accepts our key: tell the app, which sends the user to the key prompt.
        if (res.status === 401) reportAccessDenied(body?.error ?? 'The access key was not accepted.')
        if (res.status === 429) throw new Error(body?.error ?? 'Too many requests from here. Try again in a minute.')
        throw new Error(body?.error ?? `Request failed with status ${res.status}`)
    }
    if (body === undefined) throw new Error('The server sent an unreadable response.')
    return body
}

/**
 * Asks a server whether it is an eeck server that answers. Used before a chat
 * is attempted so a wrong address fails with a reason instead of a chat that
 * silently never connects.
 */
export async function checkHealth(endpoint: ServerEndpoint): Promise<{ keyRequired: boolean }> {
    let res: Response
    try {
        res = await fetch(httpUrl(endpoint, '/api/health'), { signal: AbortSignal.timeout(8000) })
    } catch {
        throw new Error('Could not reach that server. Check the address, and that it is running and reachable.')
    }
    let body: { status?: string; keyRequired?: boolean } | undefined
    try {
        body = (await res.json()) as { status?: string; keyRequired?: boolean }
    } catch {
        body = undefined
    }
    if (!res.ok || body?.status !== 'ok') throw new Error('That address answered, but it is not an eeck server.')
    return { keyRequired: body.keyRequired === true }
}

/** Succeeds only if the endpoint's access key is accepted; the server's own message says why not. */
export async function checkAccess(endpoint: ServerEndpoint): Promise<void> {
    let res: Response
    try {
        res = await fetch(httpUrl(endpoint, '/api/access'), { headers: authHeaders(endpoint), signal: AbortSignal.timeout(8000) })
    } catch {
        throw new Error('Could not reach that server. Check the address, and that it is running and reachable.')
    }
    await parseJsonOrThrow(res)
}

export async function createLink(endpoint: ServerEndpoint): Promise<LinkResponse> {
    const res = await fetch(httpUrl(endpoint, '/api/chat-link'), { method: 'POST', headers: authHeaders(endpoint) })
    return parseJsonOrThrow<LinkResponse>(res)
}

export async function getLinkStatus(endpoint: ServerEndpoint, chatId: string): Promise<StatusResponse> {
    const res = await fetch(httpUrl(endpoint, `/api/chat-link/status/${encodeURIComponent(chatId)}`), {
        headers: authHeaders(endpoint),
    })
    return parseJsonOrThrow<StatusResponse>(res)
}

export async function deleteLink(endpoint: ServerEndpoint, chatId: string, ownerToken: string): Promise<void> {
    const res = await fetch(httpUrl(endpoint, `/api/chat-link/${encodeURIComponent(chatId)}`), {
        method: 'DELETE',
        headers: { ...authHeaders(endpoint), Authorization: `Bearer ${ownerToken}` },
    })
    await parseJsonOrThrow(res)
}
