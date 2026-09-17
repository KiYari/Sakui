/**
 * Thin WebSocket transport SDK. Knows nothing about crypto or chat semantics
 * — it only moves frames (`{ type, ... }`) to and from `ws://.../ws`.
 * See `entities/session` for the layer that runs the handshake and interprets
 * frame bodies.
 */

export type JsonValue = string | number | boolean | null | { [key: string]: JsonValue } | JsonValue[]

export interface HelloFrame {
    type: 'hello'
    spki: string
}
export interface ProofFrame {
    type: 'proof'
    mac: string
}
export interface SendMessageFrame {
    type: 'message'
    body: JsonValue
    /**
     * One recipient instead of the whole room. Also the only way to reach across
     * the admission boundary: the host and a waiting joiner may address each other.
     */
    to?: string
}
/** Host only; the server ignores these from anyone else. */
export interface AdmitFrame {
    type: 'admit'
    userId: string
}
export interface RejectFrame {
    type: 'reject'
    userId: string
}
export type SendFrame = HelloFrame | ProofFrame | SendMessageFrame | AdmitFrame | RejectFrame

export interface ChallengeFrame {
    type: 'challenge'
    wrappedKey: string
    nonce: string
}
export interface WelcomeFrame {
    type: 'welcome'
    userId: string
}
export interface ReceiveMessageFrame {
    type: 'message'
    /** Assigned by the server from the sender's proven key — not something the sender can choose. */
    sender: string
    body: JsonValue
}
export interface AdmissionFrame {
    type: 'admission'
    status: 'pending' | 'admitted'
    hostId: string
}
export interface HostChangedFrame {
    type: 'host-changed'
    hostId: string
}
/** Host only: someone is waiting to be let in. */
export interface JoinRequestFrame {
    type: 'join-request'
    userId: string
}
export interface JoinRequestCancelledFrame {
    type: 'join-request-cancelled'
    userId: string
}
export interface ParticipantJoinedFrame {
    type: 'participant-joined'
    userId: string
    /** True only for a real arrival; absent when told who was already here, or on a reconnect. */
    announce?: boolean
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
export type ReceiveFrame =
    | ChallengeFrame
    | WelcomeFrame
    | AdmissionFrame
    | HostChangedFrame
    | JoinRequestFrame
    | JoinRequestCancelledFrame
    | ReceiveMessageFrame
    | ParticipantJoinedFrame
    | ParticipantLeftFrame
    | ErrorFrame

export type ConnectionStatus = 'connecting' | 'open' | 'reconnecting' | 'closed'

const INITIAL_BACKOFF_MS = 500
const MAX_BACKOFF_MS = 10_000
const DRAIN_POLL_MS = 25

/**
 * The server's own terminal close codes (see ChatCloseReasons.kt) — never
 * auto-reconnect after these. 4004 especially: the same identity opened
 * elsewhere, and reconnecting would evict that tab, which would evict this one.
 * 4007 (too many connections from this address) is deliberately absent: it is
 * the one refusal that waiting can fix.
 */
const TERMINAL_CLOSE_CODES = new Set([4000, 4001, 4002, 4003, 4004, 4005, 4006])

export class E2eeClient {
    private socket: WebSocket | undefined
    private status: ConnectionStatus = 'closed'
    private chatId: string | undefined
    private backoffMs = INITIAL_BACKOFF_MS
    private reconnectTimer: ReturnType<typeof setTimeout> | undefined
    private intentionalDisconnect = false
    private epoch = 0

    private messageHandlers = new Set<(frame: ReceiveFrame) => void>()
    private statusHandlers = new Set<(status: ConnectionStatus) => void>()

    connect(chatId: string): void {
        this.chatId = chatId
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

    /** Incremented on every successful open, so a caller can tell its socket was swapped out underneath it. */
    get connectionEpoch(): number {
        return this.epoch
    }

    /**
     * Resolves once the socket's outgoing buffer is at or below `highWaterBytes`.
     *
     * `WebSocket.send()` only queues; without this a sender would report a file
     * as sent while megabytes still sat in the browser, and closing the tab then
     * would lose it silently. Rejects if the connection that owned the queue is
     * gone — those bytes are not coming back.
     */
    async waitForDrain(highWaterBytes: number, epoch: number): Promise<void> {
        for (;;) {
            if (epoch !== this.epoch || !this.socket || this.status !== 'open') {
                throw new Error('Connection was interrupted while sending.')
            }
            if (this.socket.bufferedAmount <= highWaterBytes) return
            await new Promise((resolve) => setTimeout(resolve, DRAIN_POLL_MS))
        }
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
        if (!this.chatId) return
        this.setStatus(this.status === 'closed' ? 'connecting' : 'reconnecting')

        const url = new URL('/ws', window.location.origin)
        url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
        url.searchParams.set('chatId', this.chatId)

        const socket = new WebSocket(url)
        this.socket = socket

        socket.onopen = () => {
            this.backoffMs = INITIAL_BACKOFF_MS
            this.epoch += 1
            this.setStatus('open')
        }
        socket.onmessage = (event) => {
            const frame = JSON.parse(event.data as string) as ReceiveFrame
            this.messageHandlers.forEach((handler) => handler(frame))
        }
        socket.onclose = (event) => {
            if (this.socket !== socket) return // a newer socket already took over
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
