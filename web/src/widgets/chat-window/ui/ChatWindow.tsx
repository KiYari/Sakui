import { useState } from 'react'
import {
    Avatar,
    canSend,
    displayName,
    formatBytes,
    OwnAvatar,
    peersOf,
    type ChatMessage,
    type ConnectionState,
    type JoinRequestView,
    type PeerId,
    type Profile,
    type TransferProgress,
} from '../../../entities/session'
import { JoinRequestCard } from '../../../features/admit-peer'
import { DeleteLinkButton } from '../../../features/delete-link'
import { ProfileEditor } from '../../../features/edit-profile'
import { FileAttachButton } from '../../../features/send-file'
import { MessageComposer } from '../../../features/send-message'

interface ChatWindowProps {
    chatId: string
    /** Key fingerprint of this tab, or null until the key has loaded. */
    selfId: PeerId | null
    hostId: PeerId | null
    connection: ConnectionState
    messages: readonly ChatMessage[]
    transfers: readonly TransferProgress[]
    joinRequests: readonly JoinRequestView[]
    profiles: Readonly<Record<PeerId, Profile>>
    ownProfile: Profile
    onSend: (text: string) => Promise<void>
    onSendFile: (file: File) => Promise<void>
    onAdmit: (peerId: PeerId) => void
    onReject: (peerId: PeerId) => void
    onProfileSaved: (profile: Profile) => void
    onLeave: () => void
    onDeleted: () => void
}

export function ChatWindow(props: ChatWindowProps) {
    const { chatId, selfId, hostId, connection, messages, transfers, joinRequests, profiles, ownProfile } = props
    const [editingProfile, setEditingProfile] = useState(false)
    const isHost = selfId !== null && hostId === selfId

    return (
        <div className="screen chat-screen">
            <header className="chat-header">
                <div className="peer-status">
                    <span className={`status-dot ${peersOf(connection).length > 0 ? 'online' : 'offline'}`} />
                    {describePresence(connection)}
                </div>
                {isHost && <span className="host-badge">host</span>}
                <div className="connection-status">{describeTransport(connection)}</div>
                <button className="self-profile secondary" onClick={() => setEditingProfile((open) => !open)}>
                    <OwnAvatar profile={ownProfile} />
                    {ownProfile.name || 'Set your name'}
                </button>
                <button className="secondary" onClick={props.onLeave}>
                    Leave
                </button>
                <DeleteLinkButton chatId={chatId} onDeleted={props.onDeleted} />
            </header>

            {selfId && (
                // The first 16 characters of the fingerprint (96 bits): comparing them
                // with a peer over another channel is how to rule out a server that
                // introduced a fake participant, which the protocol alone cannot.
                <div className="self-id" title={selfId}>
                    your id: {selfId.slice(0, 16)}
                </div>
            )}

            {editingProfile && (
                <ProfileEditor
                    profile={ownProfile}
                    onSaved={(profile) => {
                        props.onProfileSaved(profile)
                        setEditingProfile(false)
                    }}
                    onCancel={() => setEditingProfile(false)}
                />
            )}

            {joinRequests.map((request) => (
                <JoinRequestCard
                    key={request.peerId}
                    peerId={request.peerId}
                    profiles={profiles}
                    onAdmit={props.onAdmit}
                    onReject={props.onReject}
                />
            ))}

            {connection.status === 'terminated' && <p className="error">{describeTermination(connection)}</p>}

            {connection.status === 'pending' ? (
                <div className="waiting-room">
                    <p>
                        Waiting for{' '}
                        <strong>{hostId && hostId in profiles ? displayName(hostId, profiles) : 'the host'}</strong> to
                        let you in…
                    </p>
                    <p className="hint">
                        They can see your name and your id. If they know you, tell them it starts with{' '}
                        <code>{selfId?.slice(0, 16)}</code>.
                    </p>
                </div>
            ) : (
                <>
                    <div className="message-list">
                        {messages.map((m) => (
                            <MessageView key={m.id} message={m} profiles={profiles} selfId={selfId} />
                        ))}
                    </div>

                    {transfers.map((t) => (
                        <p key={t.fileId} className="hint">
                            {t.direction === 'incoming' ? 'Receiving' : 'Sending'} {t.name} —{' '}
                            {Math.round((t.done / t.total) * 100)}%
                        </p>
                    ))}

                    <div className="composer">
                        <FileAttachButton enabled={canSend(connection)} onSend={props.onSendFile} />
                        <MessageComposer enabled={canSend(connection)} onSend={props.onSend} />
                    </div>
                </>
            )}
        </div>
    )
}

interface MessageViewProps {
    message: ChatMessage
    profiles: Readonly<Record<PeerId, Profile>>
    selfId: PeerId | null
}

function MessageView({ message: m, profiles, selfId }: MessageViewProps) {
    switch (m.kind) {
        case 'notice':
            return <div className="message notice">{m.text}</div>
        case 'presence':
            return <div className="message presence">{describePresenceEvent(m, profiles, selfId)}</div>
        case 'text':
        case 'file':
            return (
                <div className={`message ${m.sender === 'me' ? 'me' : 'peer'}`}>
                    {m.sender !== 'me' && (
                        <span className="message-sender" title={m.sender}>
                            <Avatar peerId={m.sender} profiles={profiles} />
                            {displayName(m.sender, profiles)}
                        </span>
                    )}
                    {m.kind === 'text' ? <span className="message-text">{m.text}</span> : <AttachmentView message={m} />}
                </div>
            )
    }
}

function describePresenceEvent(
    m: Extract<ChatMessage, { kind: 'presence' }>,
    profiles: Readonly<Record<PeerId, Profile>>,
    selfId: PeerId | null,
): string {
    const who = m.peerId === selfId ? 'You' : displayName(m.peerId, profiles)
    switch (m.event) {
        case 'joined':
            return `${who} joined the chat`
        case 'left':
            return `${who} left the chat`
        case 'host':
            return m.peerId === selfId ? 'You are now the host' : `${who} is now the host`
    }
}

function AttachmentView({ message }: { message: Extract<ChatMessage, { kind: 'file' }> }) {
    const { attachment } = message
    return (
        <span className="message-text">
            {attachment.isImage && (
                // Both send and receive paths build this object URL from the validated
                // type, so it cannot become active content; anything not on the image
                // allowlist (SVG included) falls through to the download link instead.
                <img className="message-image" src={attachment.url} alt={attachment.name} />
            )}
            <a href={attachment.url} download={attachment.name} rel="noopener noreferrer">
                {attachment.name}
            </a>{' '}
            <span className="hint">({formatBytes(attachment.size)})</span>
        </span>
    )
}

function describePresence(connection: ConnectionState): string {
    if (connection.status === 'pending') return 'Waiting to be let in'
    const count = peersOf(connection).length
    if (count === 0) return 'Nobody else here yet'
    return `${count} peer${count > 1 ? 's' : ''} online`
}

function describeTermination(connection: Extract<ConnectionState, { status: 'terminated' }>): string {
    switch (connection.code) {
        case 'REJECTED':
            return 'The host declined your request to join.'
        case 'LINK_DELETED':
            return 'This chat was deleted by its creator.'
        case 'ROOM_FULL':
            return 'This chat is full.'
        default:
            return connection.reason
    }
}

/**
 * The union means the header can say something true in every state — a dead
 * link now reads "rejected" instead of spinning on "connecting" forever.
 */
function describeTransport(connection: ConnectionState): string {
    switch (connection.status) {
        case 'connecting':
            return 'connecting'
        case 'reconnecting':
            return 'reconnecting'
        case 'joining':
            return 'joining'
        case 'pending':
            return 'waiting'
        case 'closed':
            return 'disconnected'
        case 'terminated':
            return 'ended'
        case 'waiting':
        case 'exchanging':
        case 'ready':
            return 'open'
    }
}
