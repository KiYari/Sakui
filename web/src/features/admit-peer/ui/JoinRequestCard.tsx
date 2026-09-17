import { Avatar, displayName, type PeerId, type Profile } from '../../../entities/session'

interface JoinRequestCardProps {
    peerId: PeerId
    profiles: Readonly<Record<PeerId, Profile>>
    onAdmit: (peerId: PeerId) => void
    onReject: (peerId: PeerId) => void
}

/**
 * What the host sees for someone waiting at the door. The name is whatever the
 * joiner typed and proves nothing, so the id prefix — which they can't choose —
 * is always shown beside it for comparing with the person out of band.
 */
export function JoinRequestCard({ peerId, profiles, onAdmit, onReject }: JoinRequestCardProps) {
    const hasProfile = peerId in profiles
    return (
        <div className="join-request">
            <Avatar peerId={peerId} profiles={profiles} />
            <div className="join-request-who">
                <strong>{hasProfile ? displayName(peerId, profiles) : 'Someone'}</strong> wants to join
                <span className="hint" title={peerId}>
                    {' '}
                    id {peerId.slice(0, 16)}
                </span>
            </div>
            <div className="join-request-actions">
                <button onClick={() => onAdmit(peerId)}>Let in</button>
                <button className="secondary" onClick={() => onReject(peerId)}>
                    Decline
                </button>
            </div>
        </div>
    )
}
