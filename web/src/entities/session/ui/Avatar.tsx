import { colorOf, displayName, type Profile } from '../model/profile'
import type { PeerId } from '../model/types'

interface AvatarProps {
    peerId: PeerId
    profiles: Readonly<Record<PeerId, Profile>>
}

/** A coloured initial. Colour comes from a fixed palette class, never from peer-supplied CSS. */
export function Avatar({ peerId, profiles }: AvatarProps) {
    const initial = [...displayName(peerId, profiles)][0]?.toUpperCase() ?? '?'
    return (
        <span className={`avatar avatar-color-${colorOf(peerId, profiles)}`} aria-hidden="true">
            {initial}
        </span>
    )
}

/** For this tab's own profile, which has no peer id entry in `profiles`. */
export function OwnAvatar({ profile }: { profile: Profile }) {
    const initial = [...profile.name][0]?.toUpperCase() ?? '?'
    return (
        <span className={`avatar avatar-color-${profile.color}`} aria-hidden="true">
            {initial}
        </span>
    )
}
