import { E2eeClient, type ConnectionStatus, type JsonValue, type ReceiveFrame } from './e2ee-client'
import { getOrCreateKeyPair } from './keyStore'
import { encryptMessage, decryptMessage, exportPublicKey, importPublicKey, type ChatEnvelope } from './crypto'

/** Travels as the `body` of a transport `message` frame, opaque to the server. */
interface KeyAnnouncement {
    kind: 'public-key'
    spki: string
}

/**
 * A chat envelope addressed to one recipient. The server broadcasts every
 * `message` frame to all other participants, so a room with more than one
 * peer carries several `recipient`-tagged envelopes per plaintext message —
 * each peer decrypts only the one addressed to its own userId.
 */
type AddressedEnvelope = ChatEnvelope & { recipient: string }

export interface ChatMessage {
    id: string
    /** 'me', or the sending peer's userId. */
    sender: string
    text: string
    timestamp: number
}

export interface ChatSessionCallbacks {
    onMessage: (message: ChatMessage) => void
    /** userIds of the currently connected peers (excluding ourselves). */
    onPeersChange: (peerIds: string[]) => void
    onKeysExchangedChange: (exchanged: boolean) => void
    onConnectionStatusChange: (status: ConnectionStatus) => void
}

/**
 * Combines the transport (`E2eeClient`) with the crypto primitives
 * (`crypto.ts`) and the per-chat keypair (`keyStore.ts`) into the actual
 * chat protocol: key announcement/exchange, then encrypt-before-send /
 * decrypt-on-receive. `E2eeClient` itself never sees plaintext or key
 * material — it only ever moves JSON frames.
 *
 * Any number of peers may share a room: each outgoing message is encrypted
 * separately per peer (fan-out) since the crypto primitives are single-recipient.
 */
export class ChatSession {
    private client = new E2eeClient()
    private privateKey: CryptoKey | undefined
    private myPublicKeySpki: string | undefined
    private onlinePeerIds = new Set<string>()
    private peerPublicKeys = new Map<string, CryptoKey>()
    /**
     * Guards against a dispose() that lands while start() is still awaiting
     * (e.g. React StrictMode's dev-only mount -> cleanup -> remount, which
     * runs cleanup before the async keypair lookup below has resolved).
     * Without this, a "disposed" session would resume after the await and
     * open a real WebSocket anyway, leaving stray/duplicate connections.
     */
    private disposed = false

    constructor(
        private readonly chatId: string,
        private readonly userId: string,
        private readonly callbacks: ChatSessionCallbacks,
    ) {}

    async start(): Promise<void> {
        const pair = await getOrCreateKeyPair(this.chatId)
        if (this.disposed) return
        this.privateKey = pair.privateKey
        this.myPublicKeySpki = await exportPublicKey(pair.publicKey)
        if (this.disposed) return

        this.client.onMessage((frame) => {
            this.handleFrame(frame).catch((error) => {
                // A malformed/undecryptable frame is dropped, not surfaced as a crash.
                console.error('Failed to handle incoming frame', error)
            })
        })
        this.client.onStatusChange((status) => {
            this.callbacks.onConnectionStatusChange(status)
            if (status === 'open') {
                this.announcePublicKey()
            }
        })
        this.client.connect(this.chatId, this.userId)
    }

    async sendMessage(text: string): Promise<void> {
        const recipients = [...this.peerPublicKeys.entries()]
        if (recipients.length === 0) {
            throw new Error('Cannot send: keys have not been exchanged with any peer yet.')
        }
        for (const [peerId, peerPublicKey] of recipients) {
            const envelope = await encryptMessage(text, peerPublicKey)
            const addressed: AddressedEnvelope = { ...envelope, recipient: peerId }
            this.client.send({ type: 'message', body: addressed as unknown as JsonValue })
        }
        this.callbacks.onMessage({ id: crypto.randomUUID(), sender: 'me', text, timestamp: Date.now() })
    }

    dispose(): void {
        this.disposed = true
        this.client.disconnect()
    }

    private announcePublicKey(): void {
        if (!this.myPublicKeySpki) return
        const announcement: KeyAnnouncement = { kind: 'public-key', spki: this.myPublicKeySpki }
        this.client.send({ type: 'message', body: announcement as unknown as JsonValue })
    }

    private async handleFrame(frame: ReceiveFrame): Promise<void> {
        switch (frame.type) {
            case 'participant-joined':
                this.onlinePeerIds.add(frame.userId)
                this.callbacks.onPeersChange([...this.onlinePeerIds])
                // Re-announce: the newcomer (and anyone else) may not have our key yet.
                this.announcePublicKey()
                return
            case 'participant-left':
                this.onlinePeerIds.delete(frame.userId)
                this.peerPublicKeys.delete(frame.userId)
                this.callbacks.onPeersChange([...this.onlinePeerIds])
                this.callbacks.onKeysExchangedChange(this.peerPublicKeys.size > 0)
                return
            case 'error':
                console.error(`Server error [${frame.code}]: ${frame.message}`)
                return
            case 'message':
                await this.handleMessageBody(frame.sender, frame.body)
                return
        }
    }

    private async handleMessageBody(senderId: string, body: unknown): Promise<void> {
        if (!body || typeof body !== 'object' || !('kind' in body)) return

        if ((body as { kind: unknown }).kind === 'public-key') {
            const announcement = body as unknown as KeyAnnouncement
            this.peerPublicKeys.set(senderId, await importPublicKey(announcement.spki))
            this.callbacks.onKeysExchangedChange(true)
            return
        }

        if ((body as { kind: unknown }).kind === 'chat') {
            if (!this.privateKey) return
            const envelope = body as unknown as AddressedEnvelope
            if (envelope.recipient !== this.userId) return // addressed to a different peer in this room
            const text = await decryptMessage(envelope, this.privateKey)
            this.callbacks.onMessage({ id: crypto.randomUUID(), sender: senderId, text, timestamp: Date.now() })
        }
    }
}
