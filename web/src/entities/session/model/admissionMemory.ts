import {
    browserStorage,
    keysWithPrefix,
    readJson,
    removeKey,
    writeJson,
    type KeyValueStorage,
} from '../../../shared/lib/local-store'
import type { PeerId } from './types'

export type AdmissionDecision = 'approved' | 'rejected'

/**
 * The host's past decisions for one chat, kept in this browser.
 *
 * A peer's id is the fingerprint of a key that stays with their tab across
 * reloads, so when someone already let in drops off and reconnects — or the
 * host reloads — the same request is answered without asking again. Someone
 * turned away stays turned away instead of being able to re-ask in a loop.
 *
 * Never shared with the server or other participants: it is one host's memory,
 * and the server's own admission check is what actually enforces entry.
 */
export interface AdmissionMemory {
    decisionFor(peerId: PeerId): AdmissionDecision | null
    remember(peerId: PeerId, decision: AdmissionDecision): void
}

const PREFIX = 'eeck-admission:'

/** Longer than a link lives, so a decision never expires while its chat is still usable. */
export const ADMISSION_MEMORY_MAX_AGE_MS = 48 * 60 * 60 * 1000

type StoredDecisions = Record<PeerId, { decision: AdmissionDecision; at: number }>

export function createAdmissionMemory(
    chatId: string,
    storage: KeyValueStorage | undefined = browserStorage(),
    clock: () => number = Date.now,
): AdmissionMemory {
    const key = PREFIX + chatId
    pruneAdmissionMemory(storage, clock())

    return {
        decisionFor(peerId) {
            const entry = readDecisions(storage, key)[peerId]
            if (!entry || clock() - entry.at > ADMISSION_MEMORY_MAX_AGE_MS) return null
            return entry.decision
        },
        remember(peerId, decision) {
            const decisions = readDecisions(storage, key)
            decisions[peerId] = { decision, at: clock() }
            writeJson(storage, key, decisions)
        },
    }
}

export function forgetAdmissionMemory(chatId: string, storage: KeyValueStorage | undefined = browserStorage()): void {
    removeKey(storage, PREFIX + chatId)
}

/** Drops expired decisions from every chat, and chats left with none. */
export function pruneAdmissionMemory(storage: KeyValueStorage | undefined, now: number): void {
    for (const key of keysWithPrefix(storage, PREFIX)) {
        const decisions = readDecisions(storage, key)
        const kept = Object.fromEntries(
            Object.entries(decisions).filter(([, entry]) => now - entry.at <= ADMISSION_MEMORY_MAX_AGE_MS),
        )
        if (Object.keys(kept).length === 0) removeKey(storage, key)
        else if (Object.keys(kept).length !== Object.keys(decisions).length) writeJson(storage, key, kept)
    }
}

function readDecisions(storage: KeyValueStorage | undefined, key: string): StoredDecisions {
    const raw = readJson(storage, key)
    if (typeof raw !== 'object' || raw === null) return {}
    const valid: StoredDecisions = {}
    for (const [peerId, entry] of Object.entries(raw as Record<string, unknown>)) {
        const { decision, at } = (entry ?? {}) as { decision?: unknown; at?: unknown }
        if ((decision === 'approved' || decision === 'rejected') && typeof at === 'number') {
            valid[peerId] = { decision, at }
        }
    }
    return valid
}
