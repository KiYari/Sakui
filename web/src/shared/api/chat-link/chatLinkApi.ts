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
        if (res.status === 429) throw new Error('Too many chats created from here. Try again in a minute.')
        throw new Error(body?.error ?? `Request failed with status ${res.status}`)
    }
    if (body === undefined) throw new Error('The server sent an unreadable response.')
    return body
}

export async function createLink(): Promise<LinkResponse> {
    const res = await fetch('/api/chat-link', { method: 'POST' })
    return parseJsonOrThrow<LinkResponse>(res)
}

export async function getLinkStatus(chatId: string): Promise<StatusResponse> {
    const res = await fetch(`/api/chat-link/status/${encodeURIComponent(chatId)}`)
    return parseJsonOrThrow<StatusResponse>(res)
}

export async function deleteLink(chatId: string, ownerToken: string): Promise<void> {
    const res = await fetch(`/api/chat-link/${encodeURIComponent(chatId)}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${ownerToken}` },
    })
    await parseJsonOrThrow(res)
}
