export interface LinkResponse {
    hash: string
    expired: boolean
    deleted: boolean
}

export interface StatusResponse {
    status: string
    state: string
}

interface ApiErrorBody {
    error?: string
}

async function parseJsonOrThrow<T>(res: Response): Promise<T> {
    const body = (await res.json()) as T & ApiErrorBody
    if (!res.ok) {
        throw new Error(body?.error ?? `Request failed with status ${res.status}`)
    }
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

export async function deleteLink(chatId: string): Promise<void> {
    const res = await fetch(`/api/chat-link/${encodeURIComponent(chatId)}`, { method: 'DELETE' })
    await parseJsonOrThrow(res)
}
