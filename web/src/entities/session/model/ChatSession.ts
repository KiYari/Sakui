import { E2eeClient, type ChallengeFrame, type JsonValue, type ReceiveFrame, type SendMessageFrame } from '../../../shared/api'
import {
    base64ToBytes,
    bytesToBase64,
    webCryptoProvider,
    type ChatEnvelope,
    type ChatKeyPair,
    type CryptoProvider,
} from '../../../shared/lib/crypto'
import { getOrCreateKeyPair } from '../../../shared/lib/key-store'
import type { AdmissionMemory } from './admissionMemory'
import {
    assembleChunks,
    chunkCountFor,
    CHUNK_BYTES,
    formatBytes,
    GCM_TAG_BYTES,
    isRenderableImage,
    MAX_CONCURRENT_INCOMING,
    MAX_FILE_BYTES,
    safeMimeType,
    sanitizeFileName,
    SEND_HIGH_WATER_BYTES,
    TRANSFER_IDLE_TIMEOUT_MS,
    type FileChunk,
    type FileOffer,
    type TransferProgress,
} from './attachment'
import { INITIAL_CONNECTION, reduceConnection, type ConnectionEvent, type ConnectionState } from './connection'
import { EMPTY_PROFILE, sanitizeProfile, type Profile } from './profile'
import type { ChatMessage, JoinRequestView, PeerId, PresenceEvent } from './types'

/** A transfer being reassembled from broadcast chunks. */
interface IncomingTransfer {
    sender: PeerId
    fileId: string
    name: string
    mime: string
    size: number
    chunks: number
    iv: string
    key: CryptoKey
    parts: Map<number, Uint8Array>
    idleTimer: ReturnType<typeof setTimeout>
}

/** Travels as the `body` of a transport `message` frame, opaque to the server. */
interface KeyAnnouncement {
    kind: 'public-key'
    spki: string
}

/**
 * An envelope for one recipient. Sent addressed (`to`), so the server delivers
 * it to that peer only; `recipient` is still checked on arrival, because the
 * server is not trusted to have routed it correctly.
 */
type AddressedEnvelope = ChatEnvelope & { recipient: PeerId }

/** The sender's [Profile] as JSON, encrypted exactly like a chat message. */
type ProfileEnvelope = Omit<ChatEnvelope, 'kind'> & { kind: 'profile'; recipient: PeerId }

export interface SessionSnapshot {
    readonly connection: ConnectionState
    readonly messages: readonly ChatMessage[]
    /** In-flight attachment transfers, in either direction. */
    readonly transfers: readonly TransferProgress[]
    /** This tab's own id (its key fingerprint), once the key is loaded. Show it so people can compare out of band. */
    readonly selfId: PeerId | null
    /** Who decides entry to the room, as the server last said. */
    readonly hostId: PeerId | null
    /** People waiting to be let in. Only ever non-empty while this tab is the host. */
    readonly joinRequests: readonly JoinRequestView[]
    /** Profiles peers have sent us, keyed by their id. */
    readonly profiles: Readonly<Record<PeerId, Profile>>
    readonly ownProfile: Profile
}

export interface ChatSessionOptions {
    cryptoProvider?: CryptoProvider
    loadKeyPair?: (chatId: string) => Promise<ChatKeyPair>
    profile?: Profile
    admissionMemory?: AdmissionMemory
    /** The user left on purpose: this tab's key for the chat has no further use. */
    forgetOwnKey?: () => Promise<void>
    /** The chat was deleted: nothing this browser holds for it has any further use. */
    forgetChat?: () => Promise<void>
}

/** One notice per sender per window, so a peer that keeps failing transfers can't flood the conversation. */
const FAILURE_NOTICE_WINDOW_MS = 30_000

/**
 * The chat protocol — handshake, admission, key and profile exchange, fan-out
 * encryption, decrypt-on-receive — exposed to React as an external store.
 *
 * `subscribe`/`getSnapshot` are the `useSyncExternalStore` contract: both are
 * stable arrow properties, and `getSnapshot` returns a cached object that is
 * replaced only when state actually changes. Returning a fresh object per call
 * would spin React in an infinite render loop.
 *
 * Identity: this session's id is the fingerprint of its key, the server only
 * admits it after a proof of possession, and every peer key is accepted only if
 * its fingerprint equals the id the server labelled the sender with. A key
 * therefore cannot be announced under anyone else's name — by a peer or by the
 * server.
 *
 * Admission: the server keeps a waiting joiner out of the room; this class
 * decides what crosses the doorway. A waiting joiner and the host exchange keys
 * and profiles, addressed to each other, so the host sees who is asking before
 * deciding. Chat content and files are only ever accepted from, and encrypted
 * to, admitted room members.
 */
export class ChatSession {
    private readonly client = new E2eeClient()
    private readonly cryptoProvider: CryptoProvider
    private readonly loadKeyPair: (chatId: string) => Promise<ChatKeyPair>
    private readonly admissionMemory: AdmissionMemory | undefined
    private readonly forgetOwnKey: () => Promise<void>
    private readonly forgetChat: () => Promise<void>

    private privateKey: CryptoKey | undefined
    private myPublicKeySpki: string | undefined
    private selfId: PeerId | undefined
    /** Every key whose fingerprint matched its sender — room members and people at the door alike. */
    private readonly verifiedKeys = new Map<PeerId, CryptoKey>()
    private readonly listeners = new Set<() => void>()
    private readonly incoming = new Map<string, IncomingTransfer>()
    private readonly lastFailureNoticeAt = new Map<PeerId, number>()

    /** Per socket: the server holds no memory of a previous connection, so neither may we. */
    private authenticated = false
    private challengeAnswered = false
    private admitted = false
    /** Admitted peers, as announced by the server. The only valid senders and recipients of room content. */
    private readonly roomPeers = new Set<PeerId>()
    /** Peers our current profile has been sent to on this socket. */
    private readonly profileSentTo = new Set<PeerId>()

    private snapshot: SessionSnapshot = {
        connection: INITIAL_CONNECTION,
        messages: [],
        transfers: [],
        selfId: null,
        hostId: null,
        joinRequests: [],
        profiles: {},
        ownProfile: EMPTY_PROFILE,
    }

    /**
     * Generation counter, not a one-way "disposed" flag.
     *
     * It guards the same race as before: a dispose() that lands while start()
     * is still awaiting the keypair (React StrictMode's dev-only mount ->
     * cleanup -> remount runs cleanup before that await resolves) must not let
     * the stale start open a socket afterwards.
     *
     * A permanent flag would also make the session un-restartable, and since
     * the hook memoises one instance per chat, StrictMode's remount would call
     * start() on a permanently-dead object and never connect at all. Bumping a
     * token invalidates only the in-flight start.
     */
    private generation = 0

    constructor(
        private readonly chatId: string,
        options: ChatSessionOptions = {},
    ) {
        this.cryptoProvider = options.cryptoProvider ?? webCryptoProvider
        this.loadKeyPair = options.loadKeyPair ?? getOrCreateKeyPair
        this.admissionMemory = options.admissionMemory
        this.forgetOwnKey = options.forgetOwnKey ?? (() => Promise.resolve())
        this.forgetChat = options.forgetChat ?? (() => Promise.resolve())
        this.snapshot = { ...this.snapshot, ownProfile: sanitizeProfile(options.profile) ?? EMPTY_PROFILE }
    }

    readonly subscribe = (listener: () => void): (() => void) => {
        this.listeners.add(listener)
        return () => {
            this.listeners.delete(listener)
        }
    }

    readonly getSnapshot = (): SessionSnapshot => this.snapshot

    async start(): Promise<void> {
        const generation = ++this.generation
        const pair = await this.loadKeyPair(this.chatId)
        if (generation !== this.generation) return
        this.privateKey = pair.privateKey
        this.myPublicKeySpki = await this.cryptoProvider.exportPublicKey(pair.publicKey)
        if (generation !== this.generation) return
        this.selfId = await this.cryptoProvider.fingerprint(this.myPublicKeySpki)
        if (generation !== this.generation) return

        this.update({ selfId: this.selfId })

        this.client.onMessage((frame) => {
            this.handleFrame(frame).catch((error) => {
                // A malformed/undecryptable frame is dropped, not surfaced as a crash.
                console.error('Failed to handle incoming frame', error)
            })
        })
        this.client.onStatusChange((status) => {
            this.dispatch({ type: 'transport', status })
            this.resetSocketState()
            if (status === 'open' && this.myPublicKeySpki) {
                this.client.send({ type: 'hello', spki: this.myPublicKeySpki })
            }
        })
        this.client.connect(this.chatId)
    }

    get isHost(): boolean {
        return this.selfId !== undefined && this.snapshot.hostId === this.selfId
    }

    async sendMessage(text: string): Promise<void> {
        const recipients = this.roomRecipients()
        if (recipients.length === 0) {
            throw new Error('Cannot send: keys have not been exchanged with any peer yet.')
        }
        for (const [peerId, peerPublicKey] of recipients) {
            const envelope = await this.cryptoProvider.encryptMessage(text, peerPublicKey)
            const addressed: AddressedEnvelope = { ...envelope, recipient: peerId }
            this.sendBody(addressed, peerId)
        }
        this.appendMessage({ id: crypto.randomUUID(), kind: 'text', sender: 'me', text, timestamp: Date.now() })
    }

    /**
     * Encrypts the file **once** under a fresh content key, wraps that key for
     * each peer (small, addressed frames), then broadcasts the ciphertext in
     * chunks that fit the server's frame cap. Re-encrypting per recipient the
     * way text does would multiply a multi-megabyte upload by the room size.
     *
     * Resolves only after the bytes have actually left the browser, and rejects
     * if the connection drops mid-way — a half-sent file is reported as a
     * failure to its sender instead of looking delivered.
     */
    async sendFile(file: File): Promise<void> {
        const recipients = this.roomRecipients()
        if (recipients.length === 0) {
            throw new Error('Cannot send: keys have not been exchanged with any peer yet.')
        }
        if (file.size > MAX_FILE_BYTES) {
            throw new Error(`File is too large (${formatBytes(file.size)}). Limit is ${formatBytes(MAX_FILE_BYTES)}.`)
        }

        const epoch = this.client.connectionEpoch
        const fileId = crypto.randomUUID()
        const progressId = `me:${fileId}`
        const raw = await file.arrayBuffer()
        const contentKey = await this.cryptoProvider.generateContentKey()
        const { iv, ciphertext } = await this.cryptoProvider.encryptBytes(contentKey, raw)

        const bytes = new Uint8Array(ciphertext)
        const chunks = chunkCountFor(bytes.byteLength)
        const name = sanitizeFileName(file.name)

        try {
            for (const [peerId, peerPublicKey] of recipients) {
                const offer: FileOffer = {
                    kind: 'file-offer',
                    recipient: peerId,
                    fileId,
                    name,
                    mime: file.type,
                    size: file.size,
                    chunks,
                    iv,
                    wrappedKey: await this.cryptoProvider.wrapContentKey(contentKey, peerPublicKey),
                }
                this.sendBody(offer, peerId)
            }

            this.trackProgress({ fileId: progressId, name, direction: 'outgoing', done: 0, total: chunks })
            for (let index = 0; index < chunks; index++) {
                const slice = bytes.subarray(index * CHUNK_BYTES, (index + 1) * CHUNK_BYTES)
                const chunk: FileChunk = { kind: 'file-chunk', fileId, index, data: bytesToBase64(slice) }
                this.sendBody(chunk)
                await this.client.waitForDrain(SEND_HIGH_WATER_BYTES, epoch)
                this.trackProgress({ fileId: progressId, name, direction: 'outgoing', done: index + 1, total: chunks })
            }
            await this.client.waitForDrain(0, epoch)
        } finally {
            this.clearProgress(progressId)
        }

        this.appendMessage({
            id: crypto.randomUUID(),
            kind: 'file',
            sender: 'me',
            timestamp: Date.now(),
            attachment: {
                name,
                mime: safeMimeType(file.type),
                size: file.size,
                // Same rule as the receive path: the object URL carries the
                // validated type, never the browser's guess, so the sender's own
                // copy of an SVG can't be opened as active content either.
                url: URL.createObjectURL(new Blob([raw], { type: safeMimeType(file.type) })),
                isImage: isRenderableImage(file.type),
            },
        })
    }

    /** Host only; the server ignores it from anyone else, so this is a convenience check, not the enforcement. */
    admit(peerId: PeerId): void {
        if (!this.isHost) return
        this.client.send({ type: 'admit', userId: peerId })
        this.admissionMemory?.remember(peerId, 'approved')
        this.removeJoinRequest(peerId)
    }

    reject(peerId: PeerId): void {
        if (!this.isHost) return
        this.client.send({ type: 'reject', userId: peerId })
        this.admissionMemory?.remember(peerId, 'rejected')
        this.removeJoinRequest(peerId)
    }

    /** Replaces the profile shown to others and sends it to everyone who can currently see it. */
    async updateProfile(profile: Profile): Promise<void> {
        const clean = sanitizeProfile(profile) ?? EMPTY_PROFILE
        this.update({ ownProfile: clean })
        this.profileSentTo.clear()
        for (const peerId of this.verifiedKeys.keys()) {
            await this.maybeSendProfile(peerId)
        }
    }

    /** Leaving on purpose, as opposed to closing the tab: this tab's key for the chat is discarded. */
    leave(): void {
        this.dispose()
        this.forgetOwnKey().catch((error: unknown) => console.error('Failed to forget key', error))
    }

    /**
     * This browser deleted the chat. The server's link-deleted notice would
     * trigger the same cleanup, but the page is torn down before it arrives.
     */
    discardChat(): void {
        this.dispose()
        this.forgetChat().catch((error: unknown) => console.error('Failed to clean up deleted chat', error))
    }

    dispose(): void {
        // Invalidates any start() still waiting on its keypair, so it bails out
        // instead of connecting after we've been torn down.
        this.generation += 1
        this.client.disconnect()
        // Object URLs pin their Blob in memory until explicitly revoked, so a
        // long chat with several images would leak every one of them.
        for (const message of this.snapshot.messages) {
            if (message.kind === 'file') URL.revokeObjectURL(message.attachment.url)
        }
        for (const transfer of this.incoming.values()) clearTimeout(transfer.idleTimer)
        this.incoming.clear()
    }

    private resetSocketState(): void {
        this.authenticated = false
        this.challengeAnswered = false
        this.admitted = false
        this.roomPeers.clear()
        this.profileSentTo.clear()
        if (this.snapshot.hostId !== null || this.snapshot.joinRequests.length > 0) {
            this.update({ hostId: null, joinRequests: [] })
        }
    }

    private async handleFrame(frame: ReceiveFrame): Promise<void> {
        switch (frame.type) {
            case 'challenge':
                await this.answerChallenge(frame)
                return
            case 'welcome':
                if (frame.userId !== this.selfId) {
                    // The server labelled us with a key we don't hold; nothing sent
                    // under that label would be ours.
                    this.dispatch({ type: 'rejected', reason: 'The server assigned an identity that does not match this key.' })
                    this.client.disconnect()
                    return
                }
                this.authenticated = true
                return
            case 'admission':
                this.update({ hostId: frame.hostId })
                this.dispatch({ type: 'admission', status: frame.status })
                if (frame.status === 'admitted') {
                    this.admitted = true
                    this.broadcastPublicKey()
                } else {
                    // At the door: only the host can hear us, and needs our key to see our profile.
                    this.sendPublicKeyTo(frame.hostId)
                }
                return
            case 'host-changed':
                await this.handleHostChanged(frame.hostId)
                return
            case 'join-request':
                await this.handleJoinRequest(frame.userId)
                return
            case 'join-request-cancelled':
                this.removeJoinRequest(frame.userId)
                return
            case 'participant-joined':
                await this.handlePeerJoined(frame.userId, frame.announce === true)
                return
            case 'participant-left':
                if (!this.roomPeers.delete(frame.userId)) return
                this.profileSentTo.delete(frame.userId)
                this.dropTransfersFrom(frame.userId, 'left before the transfer finished')
                this.dispatch({ type: 'peer-left', peerId: frame.userId })
                this.appendPresence(frame.userId, 'left')
                return
            case 'error':
                await this.handleError(frame.code, frame.message)
                return
            case 'message':
                await this.handleMessageBody(frame.sender, frame.body)
                return
        }
    }

    private async answerChallenge(challenge: ChallengeFrame): Promise<void> {
        // Exactly one answer per socket, and only before we're admitted: the
        // handshake must never become a signing service for arbitrary inputs.
        if (!this.privateKey || this.authenticated || this.challengeAnswered) return
        this.challengeAnswered = true
        const mac = await this.cryptoProvider.answerChallenge(challenge.wrappedKey, challenge.nonce, this.privateKey)
        this.client.send({ type: 'proof', mac })
    }

    private async handleError(code: string, message: string): Promise<void> {
        // The one refusal that isn't final: the transport retries on its own, so
        // this must not freeze the session in a terminal state.
        if (code === 'TOO_MANY_CONNECTIONS') return
        // Surfaced as a terminal state rather than only logged: the server
        // closes the socket after this, so the UI must stop saying "connecting"
        // and say why it failed.
        this.dispatch({ type: 'rejected', reason: message, code })
        if (code === 'LINK_DELETED') {
            await this.forgetChat().catch((error: unknown) => console.error('Failed to clean up deleted chat', error))
        }
    }

    private async handleHostChanged(hostId: PeerId): Promise<void> {
        const wasHost = this.isHost
        this.update({ hostId })
        if (wasHost && !this.isHost) this.update({ joinRequests: [] })
        if (this.admitted) {
            this.appendPresence(hostId, 'host')
            return
        }
        // Still waiting: the new host has never seen our key or profile.
        this.profileSentTo.delete(hostId)
        this.sendPublicKeyTo(hostId)
        await this.maybeSendProfile(hostId)
    }

    private async handleJoinRequest(peerId: PeerId): Promise<void> {
        if (!this.isHost || peerId === this.selfId) return

        const remembered = this.admissionMemory?.decisionFor(peerId) ?? null
        if (remembered === 'approved') {
            this.admit(peerId)
            return
        }
        if (remembered === 'rejected') {
            this.reject(peerId)
            return
        }

        if (!this.snapshot.joinRequests.some((r) => r.peerId === peerId)) {
            this.update({ joinRequests: [...this.snapshot.joinRequests, { peerId, requestedAt: Date.now() }] })
        }
        // Sent on every request, including ones re-sent after a host change or a
        // reconnect: whoever is asking may never have received this key.
        this.profileSentTo.delete(peerId)
        this.sendPublicKeyTo(peerId)
        await this.maybeSendProfile(peerId)
    }

    private async handlePeerJoined(peerId: PeerId, announce: boolean): Promise<void> {
        if (peerId === this.selfId) return
        this.roomPeers.add(peerId)
        this.removeJoinRequest(peerId)
        this.dispatch({ type: 'peer-joined', peerId })
        // A peer we met at the door already has a verified key.
        if (this.verifiedKeys.has(peerId)) this.dispatch({ type: 'peer-keyed', peerId })
        // Re-announce: the newcomer — or a peer's fresh connection — may not have our key yet.
        this.profileSentTo.delete(peerId)
        this.broadcastPublicKey()
        await this.maybeSendProfile(peerId)
        if (announce) this.appendPresence(peerId, 'joined')
    }

    private async handleMessageBody(senderId: PeerId, body: unknown): Promise<void> {
        if (!body || typeof body !== 'object' || !('kind' in body)) return
        const kind = (body as { kind: unknown }).kind

        // Keys and profiles may cross the doorway; everything else must come from inside the room.
        if (kind === 'public-key') {
            if (!this.canExchangeWith(senderId)) return
            await this.handleKeyAnnouncement(senderId, body as unknown as KeyAnnouncement)
            return
        }
        if (kind === 'profile') {
            if (!this.canExchangeWith(senderId)) return
            await this.handleProfile(senderId, body as unknown as ProfileEnvelope)
            return
        }
        if (!this.roomPeers.has(senderId)) return

        if (kind === 'chat') {
            if (!this.privateKey) return
            const envelope = body as unknown as AddressedEnvelope
            if (envelope.recipient !== this.selfId) return // addressed to a different peer in this room
            const text = await this.cryptoProvider.decryptMessage(envelope, this.privateKey)
            this.appendMessage({ id: crypto.randomUUID(), kind: 'text', sender: senderId, text, timestamp: Date.now() })
            return
        }

        if (kind === 'file-offer') {
            await this.handleFileOffer(senderId, body as unknown as FileOffer)
            return
        }

        if (kind === 'file-chunk') {
            await this.handleFileChunk(senderId, body as unknown as FileChunk)
        }
    }

    /**
     * The fix for key substitution. The sender id comes from the server, which
     * only admitted that connection after it proved it holds the key with that
     * fingerprint — so a key whose fingerprint differs from the sender id is not
     * that sender's key, whoever announced it, and is refused.
     */
    private async handleKeyAnnouncement(senderId: PeerId, announcement: KeyAnnouncement): Promise<void> {
        if (typeof announcement.spki !== 'string') return
        if (senderId === this.selfId) return
        if ((await this.cryptoProvider.fingerprint(announcement.spki)) !== senderId) {
            this.noticeOnce(senderId, `Ignored a key from ${shortId(senderId)} that does not match their identity.`)
            return
        }
        const isNew = !this.verifiedKeys.has(senderId)
        if (isNew) this.verifiedKeys.set(senderId, await this.cryptoProvider.importPublicKey(announcement.spki))
        if (this.roomPeers.has(senderId)) this.dispatch({ type: 'peer-keyed', peerId: senderId })
        await this.maybeSendProfile(senderId)
    }

    private async handleProfile(senderId: PeerId, envelope: ProfileEnvelope): Promise<void> {
        if (!this.privateKey || envelope.recipient !== this.selfId) return
        const json = await this.cryptoProvider.decryptMessage({ ...envelope, kind: 'chat' }, this.privateKey)
        let parsed: unknown
        try {
            parsed = JSON.parse(json)
        } catch {
            return
        }
        const profile = sanitizeProfile(parsed)
        if (!profile) return
        this.update({ profiles: { ...this.snapshot.profiles, [senderId]: profile } })
    }

    /**
     * Who this tab may swap keys and profiles with right now: room members once
     * inside; while waiting, only the host; as host, also whoever is at the door.
     */
    private canExchangeWith(peerId: PeerId): boolean {
        if (this.admitted) {
            return this.roomPeers.has(peerId) || (this.isHost && this.snapshot.joinRequests.some((r) => r.peerId === peerId))
        }
        return this.authenticated && peerId === this.snapshot.hostId
    }

    private async maybeSendProfile(peerId: PeerId): Promise<void> {
        if (this.profileSentTo.has(peerId) || !this.canExchangeWith(peerId)) return
        const peerKey = this.verifiedKeys.get(peerId)
        if (!peerKey) return
        this.profileSentTo.add(peerId)
        const envelope = await this.cryptoProvider.encryptMessage(JSON.stringify(this.snapshot.ownProfile), peerKey)
        const body: ProfileEnvelope = { ...envelope, kind: 'profile', recipient: peerId }
        this.sendBody(body, peerId)
    }

    private broadcastPublicKey(): void {
        if (!this.myPublicKeySpki || !this.authenticated || !this.admitted) return
        const announcement: KeyAnnouncement = { kind: 'public-key', spki: this.myPublicKeySpki }
        this.sendBody(announcement)
    }

    private sendPublicKeyTo(peerId: PeerId): void {
        if (!this.myPublicKeySpki || !this.authenticated) return
        const announcement: KeyAnnouncement = { kind: 'public-key', spki: this.myPublicKeySpki }
        this.sendBody(announcement, peerId)
    }

    /** Room members we hold a verified key for — the only recipients room content is ever encrypted to. */
    private roomRecipients(): [PeerId, CryptoKey][] {
        return [...this.roomPeers].flatMap((peerId) => {
            const key = this.verifiedKeys.get(peerId)
            return key ? [[peerId, key] as [PeerId, CryptoKey]] : []
        })
    }

    private sendBody(body: object, to?: PeerId): void {
        const frame: SendMessageFrame = { type: 'message', body: body as unknown as JsonValue }
        if (to !== undefined) frame.to = to
        this.client.send(frame)
    }

    private removeJoinRequest(peerId: PeerId): void {
        if (!this.snapshot.joinRequests.some((r) => r.peerId === peerId)) return
        this.update({ joinRequests: this.snapshot.joinRequests.filter((r) => r.peerId !== peerId) })
    }

    private async handleFileOffer(senderId: PeerId, offer: FileOffer): Promise<void> {
        if (!this.privateKey) return
        if (offer.recipient !== this.selfId) return // the offer for a different peer in this room
        if (typeof offer.fileId !== 'string' || typeof offer.iv !== 'string' || typeof offer.wrappedKey !== 'string') return

        // The sender is a stranger from a link: treat every declared number as a
        // claim, and refuse before allocating anything.
        if (!Number.isInteger(offer.size) || offer.size < 0 || offer.size > MAX_FILE_BYTES) return
        // The chunk count is fully determined by the size, so it is checked for
        // equality, not just a range — nothing about the buffer is left to the sender.
        if (offer.chunks !== chunkCountFor(offer.size + GCM_TAG_BYTES)) return

        // Keyed by sender as well as fileId. The fileId travels in the clear to the
        // whole room; keyed by it alone, any other participant could re-offer the
        // same id and replace a transfer that is already underway.
        const key = transferKey(senderId, offer.fileId)
        if (this.incoming.has(key)) return
        if (this.incoming.size >= MAX_CONCURRENT_INCOMING) {
            this.noticeOnce(senderId, `Declined a file from ${shortId(senderId)}: too many transfers in progress.`)
            return
        }

        const name = sanitizeFileName(String(offer.name))
        const contentKey = await this.cryptoProvider.unwrapContentKey(offer.wrappedKey, this.privateKey)
        // Re-check after the await: another offer could have taken the slot meanwhile.
        if (this.incoming.has(key) || this.incoming.size >= MAX_CONCURRENT_INCOMING) return

        this.incoming.set(key, {
            sender: senderId,
            fileId: offer.fileId,
            name,
            mime: String(offer.mime),
            size: offer.size,
            chunks: offer.chunks,
            iv: offer.iv,
            key: contentKey,
            parts: new Map<number, Uint8Array>(),
            idleTimer: this.armIdleTimer(key),
        })
        this.trackProgress({ fileId: key, name, direction: 'incoming', done: 0, total: offer.chunks })
    }

    private async handleFileChunk(senderId: PeerId, chunk: FileChunk): Promise<void> {
        // Looked up by the *authenticated* sender: a chunk from anyone else for the
        // same fileId finds no transfer and is dropped, so a third participant can
        // no longer inject garbage and sink someone else's file.
        const key = transferKey(senderId, chunk.fileId)
        const transfer = this.incoming.get(key)
        if (!transfer) return
        if (!Number.isInteger(chunk.index) || chunk.index < 0 || chunk.index >= transfer.chunks) return
        if (typeof chunk.data !== 'string') return

        const part = base64ToBytes(chunk.data)
        if (part.byteLength === 0 || part.byteLength > CHUNK_BYTES) return

        transfer.parts.set(chunk.index, part)
        clearTimeout(transfer.idleTimer)
        transfer.idleTimer = this.armIdleTimer(key)
        this.trackProgress({ fileId: key, name: transfer.name, direction: 'incoming', done: transfer.parts.size, total: transfer.chunks })
        if (transfer.parts.size < transfer.chunks) return

        this.forgetTransfer(key)

        try {
            const ciphertext = assembleChunks(transfer.parts, transfer.chunks)
            if (ciphertext.byteLength !== transfer.size + GCM_TAG_BYTES) {
                throw new Error('Transfer length does not match the offer.')
            }
            // GCM verifies the whole file here; a tampered or truncated transfer
            // throws rather than surfacing as a corrupt image.
            const plaintext = await this.cryptoProvider.decryptBytes(transfer.key, transfer.iv, ciphertext)

            const mime = safeMimeType(transfer.mime)
            this.appendMessage({
                id: crypto.randomUUID(),
                kind: 'file',
                sender: transfer.sender,
                timestamp: Date.now(),
                attachment: {
                    name: transfer.name,
                    mime,
                    size: plaintext.byteLength,
                    // Built with the validated type, never the sender's claim, so an
                    // "image/svg+xml" label cannot turn into active content.
                    url: URL.createObjectURL(new Blob([plaintext], { type: mime })),
                    isImage: isRenderableImage(transfer.mime),
                },
            })
        } catch {
            // Previously this vanished into the console and the file just never appeared.
            this.noticeOnce(transfer.sender, `Could not decrypt "${transfer.name}" from ${shortId(transfer.sender)}.`)
        }
    }

    private armIdleTimer(key: string): ReturnType<typeof setTimeout> {
        return setTimeout(() => {
            const transfer = this.incoming.get(key)
            if (!transfer) return
            this.forgetTransfer(key)
            this.noticeOnce(transfer.sender, `"${transfer.name}" from ${shortId(transfer.sender)} stalled and was discarded.`)
        }, TRANSFER_IDLE_TIMEOUT_MS)
    }

    private dropTransfersFrom(senderId: PeerId, reason: string): void {
        for (const [key, transfer] of [...this.incoming.entries()]) {
            if (transfer.sender !== senderId) continue
            this.forgetTransfer(key)
            this.noticeOnce(senderId, `"${transfer.name}" was not received: ${shortId(senderId)} ${reason}.`)
        }
    }

    private forgetTransfer(key: string): void {
        const transfer = this.incoming.get(key)
        if (transfer) clearTimeout(transfer.idleTimer)
        this.incoming.delete(key)
        this.clearProgress(key)
    }

    private noticeOnce(senderId: PeerId, text: string): void {
        const now = Date.now()
        const last = this.lastFailureNoticeAt.get(senderId)
        if (last !== undefined && now - last < FAILURE_NOTICE_WINDOW_MS) return
        this.lastFailureNoticeAt.set(senderId, now)
        this.appendMessage({ id: crypto.randomUUID(), kind: 'notice', text, timestamp: now })
    }

    private appendPresence(peerId: PeerId, event: PresenceEvent): void {
        this.appendMessage({ id: crypto.randomUUID(), kind: 'presence', peerId, event, timestamp: Date.now() })
    }

    private trackProgress(progress: TransferProgress): void {
        const others = this.snapshot.transfers.filter((t) => t.fileId !== progress.fileId)
        this.update({ transfers: [...others, progress] })
    }

    private clearProgress(fileId: string): void {
        if (!this.snapshot.transfers.some((t) => t.fileId === fileId)) return
        this.update({ transfers: this.snapshot.transfers.filter((t) => t.fileId !== fileId) })
    }

    private dispatch(event: ConnectionEvent): void {
        const next = reduceConnection(this.snapshot.connection, event)
        // The reducer returns the identical reference when an event changes
        // nothing; skipping the write avoids a pointless React re-render.
        if (next === this.snapshot.connection) return
        this.update({ connection: next })
    }

    private appendMessage(message: ChatMessage): void {
        this.update({ messages: [...this.snapshot.messages, message] })
    }

    private update(patch: Partial<SessionSnapshot>): void {
        this.snapshot = { ...this.snapshot, ...patch }
        this.listeners.forEach((listener) => listener())
    }
}

function transferKey(senderId: PeerId, fileId: string): string {
    return `${senderId}:${fileId}`
}

export function shortId(peerId: PeerId): string {
    return peerId.slice(0, 8)
}
