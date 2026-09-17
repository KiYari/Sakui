import type { ConnectionStatus } from '../../../shared/api'
import type { PeerId } from './types'

/**
 * Connection lifecycle as one discriminated union instead of a handful of
 * parallel booleans.
 *
 * The point is what becomes *unrepresentable*: there is no way to express
 * "keys exchanged but nobody is here", because the only state that carries
 * `keyed` also requires a non-empty `peers`, and neither exists unless the
 * socket is open. With separate `peerIds` / `keysExchanged` / `status` flags
 * that combination is one missed `setState` away, and it silently enables the
 * composer for a room the user is alone in.
 *
 * Every transition returns the *identical* state object when an event changes
 * nothing, so the store can skip notifying React at all.
 */

/** A `readonly [T, ...T[]]` cannot be empty — that is the whole invariant. */
type NonEmpty<T> = readonly [T, ...T[]]

export type ConnectionState =
    | { status: 'connecting' }
    | { status: 'reconnecting' }
    /** Socket is open; the handshake and the room's admission decision are still to come. */
    | { status: 'joining' }
    /** Waiting for the host to let this connection in. Sees nothing of the room. */
    | { status: 'pending' }
    /** Admitted, but nobody else is in the room yet. */
    | { status: 'waiting' }
    /** Peers are present; none has announced a usable public key yet. */
    | { status: 'exchanging'; peers: NonEmpty<PeerId> }
    /** At least one peer can be encrypted to — the only state that may send. */
    | { status: 'ready'; peers: NonEmpty<PeerId>; keyed: NonEmpty<PeerId> }
    /** Server refused or ended the connection; never retried. `code` is the server's error code when there is one. */
    | { status: 'terminated'; reason: string; code?: string }
    | { status: 'closed' }

export type ConnectionEvent =
    | { type: 'transport'; status: ConnectionStatus }
    | { type: 'peer-joined'; peerId: PeerId }
    | { type: 'peer-left'; peerId: PeerId }
    | { type: 'peer-keyed'; peerId: PeerId }
    | { type: 'admission'; status: 'pending' | 'admitted' }
    | { type: 'rejected'; reason: string; code?: string }

export const INITIAL_CONNECTION: ConnectionState = { status: 'connecting' }

export function reduceConnection(state: ConnectionState, event: ConnectionEvent): ConnectionState {
    switch (event.type) {
        case 'rejected':
            return { status: 'terminated', reason: event.reason, code: event.code }

        case 'admission':
            if (event.status === 'pending') {
                return state.status === 'joining' ? { status: 'pending' } : state
            }
            // Only a connection still at the door moves inside; one already in the
            // room keeps its peers.
            return state.status === 'joining' || state.status === 'pending' ? { status: 'waiting' } : state

        case 'transport': {
            // A refused session must not crawl back to life on a stray status event.
            if (state.status === 'terminated') return state
            switch (event.status) {
                case 'connecting':
                    return state.status === 'connecting' ? state : { status: 'connecting' }
                case 'reconnecting':
                    return state.status === 'reconnecting' ? state : { status: 'reconnecting' }
                case 'closed':
                    return state.status === 'closed' ? state : { status: 'closed' }
                case 'open':
                    // A fresh socket is a fresh join: it has to be admitted again, the
                    // server re-announces who is present, and every peer must re-key
                    // before we can encrypt to it. Carrying peers across a reconnect
                    // would leave us "ready" to encrypt with keys the new session never
                    // agreed on.
                    return state.status === 'joining' ? state : { status: 'joining' }
            }
            return state
        }

        case 'peer-joined': {
            if (!isOnline(state)) return state
            const peers = withPeer(peersOf(state), event.peerId)
            if (peers === peersOf(state)) return state
            return online(peers, keyedOf(state))
        }

        case 'peer-left': {
            if (!isOnline(state)) return state
            const peers = peersOf(state)
            const keyed = keyedOf(state)
            // filter() always allocates, so check membership first — an unchanged
            // state must come back as the same reference (see below).
            if (!peers.includes(event.peerId) && !keyed.includes(event.peerId)) return state
            return online(
                peers.filter((id) => id !== event.peerId),
                keyed.filter((id) => id !== event.peerId),
            )
        }

        case 'peer-keyed': {
            if (!isOnline(state)) return state
            // A key announcement can arrive before the join notice; treat it as
            // proof of presence rather than dropping the key on the floor.
            const peers = withPeer(peersOf(state), event.peerId)
            const keyed = withPeer(keyedOf(state), event.peerId)
            if (peers === peersOf(state) && keyed === keyedOf(state)) return state
            return online(peers, keyed)
        }
    }
}

/** The only state permitted to encrypt and send. */
export function canSend(state: ConnectionState): state is Extract<ConnectionState, { status: 'ready' }> {
    return state.status === 'ready'
}

export function peersOf(state: ConnectionState): readonly PeerId[] {
    return state.status === 'exchanging' || state.status === 'ready' ? state.peers : []
}

export function keyedOf(state: ConnectionState): readonly PeerId[] {
    return state.status === 'ready' ? state.keyed : []
}

function isOnline(state: ConnectionState): boolean {
    return state.status === 'waiting' || state.status === 'exchanging' || state.status === 'ready'
}

function withPeer(ids: readonly PeerId[], id: PeerId): readonly PeerId[] {
    return ids.includes(id) ? ids : [...ids, id]
}

/** Picks the right online state from the peer sets, keeping the non-empty invariants honest. */
function online(peers: readonly PeerId[], keyed: readonly PeerId[]): ConnectionState {
    if (peers.length === 0) return { status: 'waiting' }
    // Length is checked here, so these two casts are the single place where the
    // non-empty tuple type is asserted rather than proven by the compiler.
    const presentPeers = peers as NonEmpty<PeerId>
    if (keyed.length === 0) return { status: 'exchanging', peers: presentPeers }
    return { status: 'ready', peers: presentPeers, keyed: keyed as NonEmpty<PeerId> }
}
