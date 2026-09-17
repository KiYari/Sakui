import { describe, expect, it } from 'vitest'
import {
    canSend,
    INITIAL_CONNECTION,
    keyedOf,
    peersOf,
    reduceConnection,
    type ConnectionEvent,
    type ConnectionState,
} from '../model/connection'

/** Folds a sequence of events so each test reads as a scenario, not a chain of calls. */
function run(events: ConnectionEvent[], from: ConnectionState = INITIAL_CONNECTION): ConnectionState {
    return events.reduce(reduceConnection, from)
}

const socketOpen: ConnectionEvent = { type: 'transport', status: 'open' }
const admitted: ConnectionEvent = { type: 'admission', status: 'admitted' }
/** Opening a socket and being let into the room. */
const enter: ConnectionEvent[] = [socketOpen, admitted]

describe('connection state machine', () => {
    it('is not sendable until a peer has announced a key', () => {
        expect(canSend(run(enter))).toBe(false)
        expect(canSend(run([...enter, { type: 'peer-joined', peerId: 'alice' }]))).toBe(false)
        expect(canSend(run([...enter, { type: 'peer-joined', peerId: 'alice' }, { type: 'peer-keyed', peerId: 'alice' }]))).toBe(true)
    })

    it('reports the room empty again once the only peer leaves', () => {
        const state = run([
            ...enter,
            { type: 'peer-joined', peerId: 'alice' },
            { type: 'peer-keyed', peerId: 'alice' },
            { type: 'peer-left', peerId: 'alice' },
        ])

        expect(state.status).toBe('waiting')
        expect(canSend(state)).toBe(false)
        expect(peersOf(state)).toEqual([])
        expect(keyedOf(state)).toEqual([])
    })

    it('stays sendable while at least one keyed peer remains', () => {
        const state = run([
            ...enter,
            { type: 'peer-joined', peerId: 'alice' },
            { type: 'peer-keyed', peerId: 'alice' },
            { type: 'peer-joined', peerId: 'bob' },
            { type: 'peer-keyed', peerId: 'bob' },
            { type: 'peer-left', peerId: 'alice' },
        ])

        expect(canSend(state)).toBe(true)
        expect(peersOf(state)).toEqual(['bob'])
        expect(keyedOf(state)).toEqual(['bob'])
    })

    it('treats a key announcement as proof of presence when it beats the join notice', () => {
        const state = run([...enter, { type: 'peer-keyed', peerId: 'carol' }])

        expect(peersOf(state)).toEqual(['carol'])
        expect(canSend(state)).toBe(true)
    })

    it('drops every peer on reconnect, because the new session has agreed no keys', () => {
        const state = run([
            ...enter,
            { type: 'peer-joined', peerId: 'alice' },
            { type: 'peer-keyed', peerId: 'alice' },
            { type: 'transport', status: 'reconnecting' },
            socketOpen,
        ])

        // Carrying keys across a reconnect would leave us "ready" to encrypt with
        // material the re-joined room never exchanged — and the new socket has to
        // be admitted all over again.
        expect(state.status).toBe('joining')
        expect(canSend(state)).toBe(false)
    })

    it('never revives a rejected session', () => {
        const rejected = run([{ type: 'rejected', reason: 'This chat link does not exist or is no longer active.' }])
        expect(rejected.status).toBe('terminated')

        const afterTraffic = run([...enter, { type: 'peer-joined', peerId: 'alice' }], rejected)
        expect(afterTraffic).toEqual(rejected)
    })

    it('ignores peer events that arrive while the socket is not open', () => {
        const state = run([{ type: 'peer-joined', peerId: 'ghost' }])

        expect(state).toBe(INITIAL_CONNECTION)
        expect(peersOf(state)).toEqual([])
    })

    it('returns the identical state object when an event changes nothing, so React can skip the render', () => {
        const joined = run([...enter, { type: 'peer-joined', peerId: 'alice' }])

        expect(reduceConnection(joined, { type: 'peer-joined', peerId: 'alice' })).toBe(joined)
        expect(reduceConnection(joined, { type: 'peer-left', peerId: 'nobody' })).toBe(joined)
    })

    it('keeps a waiting joiner out of the room until admitted', () => {
        const pending = run([socketOpen, { type: 'admission', status: 'pending' }])
        expect(pending.status).toBe('pending')

        // Peer events can't reach a pending connection through the server; if one
        // did anyway, it must not make the room look joined.
        const stillPending = run([{ type: 'peer-joined', peerId: 'alice' }, { type: 'peer-keyed', peerId: 'alice' }], pending)
        expect(stillPending).toBe(pending)
        expect(canSend(stillPending)).toBe(false)

        expect(run([admitted], pending).status).toBe('waiting')
    })

    it('an admission notice does not reset a connection that is already in the room', () => {
        const ready = run([...enter, { type: 'peer-joined', peerId: 'alice' }, { type: 'peer-keyed', peerId: 'alice' }])

        expect(reduceConnection(ready, admitted)).toBe(ready)
        expect(reduceConnection(ready, { type: 'admission', status: 'pending' })).toBe(ready)
    })

    it('keeps the server error code on termination', () => {
        const state = run([socketOpen, { type: 'rejected', reason: 'declined', code: 'REJECTED' }])

        expect(state).toEqual({ status: 'terminated', reason: 'declined', code: 'REJECTED' })
    })
})
