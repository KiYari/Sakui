import { E2eeClient, type ConnectionStatus, type JsonValue, type ReceiveFrame } from './e2ee-client'
import { getOrCreateKeyPair } from './keyStore'
import { encryptMessage, decryptMessage, exportPublicKey, importPublicKey, type ChatEnvelope } from './crypto'

/** Travels as the `body` of a transport `message` frame, opaque to the server. */
interface KeyAnnouncement {
    kind: 'public-key'
    spki: string
}

export interface ChatMessage {
    id: string
    sender: 'me' | 'peer'
    text: string
    timestamp: number
}

export interface ChatSessionCallbacks {
    onMessage: (message: ChatMessage) => void
    onPeerOnlineChange: (online: boolean) => void
    onKeysExchangedChange: (exchanged: boolean) => void
    onConnectionStatusChange: (status: ConnectionStatus) => void
}

/**
 * Combines the transport (`E2eeClient`) with the crypto primitives
 * (`crypto.ts`) and the per-chat keypair (`keyStore.ts`) into the actual
 * chat protocol: key announcement/exchange, then encrypt-before-send /
 * decrypt-on-receive. `E2eeClient` itself never sees plaintext or key
 * material — it only ever moves JSON frames.
 */
export class ChatSession {
    private client = new E2eeClient()
    private privateKey: CryptoKey | undefined
    private myPublicKeySpki: string | undefined
    private peerPublicKey: CryptoKey | undefined

    constructor(
        private readonly chatId: string,
        private readonly userId: string,
        private readonly callbacks: ChatSessionCallbacks,
    ) {}

    async start(): Promise<void> {
        const pair = await getOrCreateKeyPair(this.chatId)
        this.privateKey = pair.privateKey
        this.myPublicKeySpki = await exportPublicKey(pair.publicKey)

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
        if (!this.peerPublicKey) {
            throw new Error('Cannot send: keys have not been exchanged with the peer yet.')
        }
        const envelope = await encryptMessage(text, this.peerPublicKey)
        this.client.send({ type: 'message', body: envelope as unknown as JsonValue })
        this.callbacks.onMessage({ id: crypto.randomUUID(), sender: 'me', text, timestamp: Date.now() })
    }

    dispose(): void {
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
                this.callbacks.onPeerOnlineChange(true)
                // Re-announce: we may have sent our key before anyone was here to receive it.
                this.announcePublicKey()
                return
            case 'participant-left':
                this.callbacks.onPeerOnlineChange(false)
                this.peerPublicKey = undefined
                this.callbacks.onKeysExchangedChange(false)
                return
            case 'error':
                console.error(`Server error [${frame.code}]: ${frame.message}`)
                return
            case 'message':
                await this.handleMessageBody(frame.body)
                return
        }
    }

    private async handleMessageBody(body: unknown): Promise<void> {
        if (!body || typeof body !== 'object' || !('kind' in body)) return

        if ((body as { kind: unknown }).kind === 'public-key') {
            const announcement = body as unknown as KeyAnnouncement
            this.peerPublicKey = await importPublicKey(announcement.spki)
            this.callbacks.onKeysExchangedChange(true)
            return
        }

        if ((body as { kind: unknown }).kind === 'chat') {
            if (!this.privateKey) return
            const envelope = body as unknown as ChatEnvelope
            const text = await decryptMessage(envelope, this.privateKey)
            this.callbacks.onMessage({ id: crypto.randomUUID(), sender: 'peer', text, timestamp: Date.now() })
        }
    }
}
