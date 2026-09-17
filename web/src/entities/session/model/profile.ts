import { browserStorage, readJson, writeJson, type KeyValueStorage } from '../../../shared/lib/local-store'
import type { PeerId } from './types'

/**
 * What a participant chooses to show about themselves. Sent end-to-end
 * encrypted, so the server never learns it, and kept in this browser only.
 *
 * It is a *label*, not an identity: anyone can pick any name. The id beside it
 * (the key fingerprint) is what can't be forged, which is why the UI always
 * shows both.
 */
export interface Profile {
    name: string
    /** Index into the avatar palette, 0..PROFILE_COLOR_COUNT-1. */
    color: number
}

export const PROFILE_NAME_MAX_LENGTH = 32
export const PROFILE_COLOR_COUNT = 8

const OWN_PROFILE_KEY = 'eeck-profile'

export const EMPTY_PROFILE: Profile = { name: '', color: 0 }

/**
 * Normalises a profile from any source — a form, storage, or a peer. Peers are
 * strangers holding a link, so nothing about the shape is trusted: control and
 * bidi-override characters are stripped (they can make a name render as
 * someone else's), whitespace collapsed, length capped, colour clamped.
 */
export function sanitizeProfile(raw: unknown): Profile | null {
    if (typeof raw !== 'object' || raw === null) return null
    const { name, color } = raw as { name?: unknown; color?: unknown }
    if (typeof name !== 'string' || typeof color !== 'number') return null
    const cleanName = [...name.replace(/[\p{Cc}\p{Cf}]/gu, '').replace(/\s+/g, ' ').trim()]
        .slice(0, PROFILE_NAME_MAX_LENGTH)
        .join('')
    const cleanColor = Number.isInteger(color) && color >= 0 && color < PROFILE_COLOR_COUNT ? color : 0
    return { name: cleanName, color: cleanColor }
}

export function loadOwnProfile(storage: KeyValueStorage | undefined = browserStorage()): Profile {
    return sanitizeProfile(readJson(storage, OWN_PROFILE_KEY)) ?? EMPTY_PROFILE
}

export function saveOwnProfile(profile: Profile, storage: KeyValueStorage | undefined = browserStorage()): Profile {
    const clean = sanitizeProfile(profile) ?? EMPTY_PROFILE
    writeJson(storage, OWN_PROFILE_KEY, clean)
    return clean
}

/** The name to show for a peer: their chosen one, else the start of their fingerprint. */
export function displayName(peerId: PeerId, profiles: Readonly<Record<PeerId, Profile>>): string {
    return profiles[peerId]?.name || peerId.slice(0, 8)
}

/** Peers without a profile get a stable colour derived from their id, so avatars still tell people apart. */
export function colorOf(peerId: PeerId, profiles: Readonly<Record<PeerId, Profile>>): number {
    const profile = profiles[peerId]
    if (profile) return profile.color
    let hash = 0
    for (const char of peerId) hash = (hash * 31 + char.charCodeAt(0)) >>> 0
    return hash % PROFILE_COLOR_COUNT
}
