/**
 * Thin WebSocket transport SDK. Knows nothing about crypto or chat semantics
 * — it only moves frames (`{ type, ... }`) to and from `ws://.../ws`.
 * See `chatSession.ts` for the layer that interprets frame bodies.
 */

export type JsonValue = string | number | boolean | null | { [key: string]: JsonValue } | JsonValue[]

export interface SendMessageFrame {
    type: 'message'
    body: JsonValue
}
export type SendFrame = SendMessageFrame

export interface ReceiveMessageFrame {
    type: 'message'
    sender: string
    body: JsonValue
}
export interface ParticipantJoinedFrame {
    type: 'participant-joined'
    userId: string
}
export interface ParticipantLeftFrame {
    type: 'participant-left'
    userId: string
}
export interface ErrorFrame {
    type: 'error'
    code: string
    message: string
}
export type ReceiveFrame = ReceiveMessageFrame | ParticipantJoinedFrame | ParticipantLeftFrame | ErrorFrame

export type ConnectionStatus = 'connecting' | 'open' | 'reconnecting' | 'closed'

const INITIAL_BACKOFF_MS = 500
const MAX_BACKOFF_MS = 10_000

/** The server's own terminal rejection close codes (see ChatCloseReasons.kt) — never auto-reconnect after these. */
const TERMINAL_CLOSE_CODES = new Set([4000])

export class E2eeClient {
    private socket: WebSocket | undefined
    private status: ConnectionStatus = 'closed'
    private chatId: string | undefined
    private userId: string | undefined
    private backoffMs = INITIAL_BACKOFF_MS
    private reconnectTimer: ReturnType<typeof setTimeout> | undefined
    private intentionalDisconnect = false

    private messageHandlers = new Set<(frame: ReceiveFrame) => void>()
    private statusHandlers = new Set<(status: ConnectionStatus) => void>()

    connect(chatId: string, userId: string): void {
        this.chatId = chatId
        this.userId = userId
        this.intentionalDisconnect = false
        this.backoffMs = INITIAL_BACKOFF_MS
        this.open()
    }

    send(frame: SendFrame): void {
        if (!this.socket || this.status !== 'open') {
            throw new Error('Cannot send: connection is not open.')
        }
        this.socket.send(JSON.stringify(frame))
    }

    onMessage(handler: (frame: ReceiveFrame) => void): () => void {
        this.messageHandlers.add(handler)
        return () => this.messageHandlers.delete(handler)
    }

    onStatusChange(handler: (status: ConnectionStatus) => void): () => void {
        this.statusHandlers.add(handler)
        return () => this.statusHandlers.delete(handler)
    }

    disconnect(): void {
        this.intentionalDisconnect = true
        if (this.reconnectTimer !== undefined) {
            clearTimeout(this.reconnectTimer)
            this.reconnectTimer = undefined
        }
        this.socket?.close()
        this.socket = undefined
        this.setStatus('closed')
    }

    private open(): void {
        if (!this.chatId || !this.userId) return
        this.setStatus(this.status === 'closed' ? 'connecting' : 'reconnecting')

        const url = new URL('/ws', window.location.origin)
        url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
        url.searchParams.set('chatId', this.chatId)
        url.searchParams.set('userId', this.userId)

        const socket = new WebSocket(url)
        this.socket = socket

        socket.onopen = () => {
            this.backoffMs = INITIAL_BACKOFF_MS
            this.setStatus('open')
        }
        socket.onmessage = (event) => {
            const frame = JSON.parse(event.data as string) as ReceiveFrame
            this.messageHandlers.forEach((handler) => handler(frame))
        }
        socket.onclose = (event) => {
            this.socket = undefined
            if (this.intentionalDisconnect || TERMINAL_CLOSE_CODES.has(event.code)) {
                this.setStatus('closed')
                return
            }
            this.scheduleReconnect()
        }
    }

    private scheduleReconnect(): void {
        this.setStatus('reconnecting')
        this.reconnectTimer = setTimeout(() => this.open(), this.backoffMs)
        this.backoffMs = Math.min(this.backoffMs * 2, MAX_BACKOFF_MS)
    }

    private setStatus(status: ConnectionStatus): void {
        this.status = status
        this.statusHandlers.forEach((handler) => handler(status))
    }
}
