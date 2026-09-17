import type { Attachment } from './attachment'

/** base64url(SHA-256(SPKI)) of the participant's public key — see `fingerprint()`. */
export type PeerId = string

interface ChatMessageBase {
    id: string
    /** `'me'` for locally-sent messages, otherwise the sending peer's id. */
    sender: 'me' | PeerId
    timestamp: number
}

/**
 * A union rather than a `text` plus an optional `attachment`: a message is one
 * or the other, and the renderer should not have to decide what a message with
 * both — or neither — means.
 *
 * `notice` has no sender on purpose: it is the app talking (a transfer failed,
 * a peer sent an unverifiable key), and must never be mistakable for a peer.
 * `presence` is the room changing — someone arrived, left, or took over as
 * host — and carries the peer id rather than a name, so the label shown is
 * always the peer's current profile, even one that arrives later.
 */
export type ChatMessage =
    | (ChatMessageBase & { kind: 'text'; text: string })
    | (ChatMessageBase & { kind: 'file'; attachment: Attachment })
    | { kind: 'notice'; id: string; timestamp: number; text: string }
    | { kind: 'presence'; id: string; timestamp: number; peerId: PeerId; event: PresenceEvent }

export type PresenceEvent = 'joined' | 'left' | 'host'

/** Someone waiting at the door, as the host sees them. */
export interface JoinRequestView {
    peerId: PeerId
    requestedAt: number
}
