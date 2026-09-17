import { useCallback } from 'react'
import { useChatSession, type PeerId, type Profile } from '../../../entities/session'
import { ChatWindow } from '../../../widgets/chat-window'

interface ChatPageProps {
    chatId: string
    onLeave: () => void
}

export function ChatPage({ chatId, onLeave }: ChatPageProps) {
    const { session, snapshot } = useChatSession(chatId)

    const handleSend = useCallback((text: string) => session.sendMessage(text), [session])
    const handleSendFile = useCallback((file: File) => session.sendFile(file), [session])
    const handleAdmit = useCallback((peerId: PeerId) => session.admit(peerId), [session])
    const handleReject = useCallback((peerId: PeerId) => session.reject(peerId), [session])
    const handleProfileSaved = useCallback((profile: Profile) => void session.updateProfile(profile), [session])
    const handleLeave = useCallback(() => {
        session.leave()
        onLeave()
    }, [session, onLeave])
    const handleDeleted = useCallback(() => {
        session.discardChat()
        onLeave()
    }, [session, onLeave])

    return (
        <ChatWindow
            chatId={chatId}
            selfId={snapshot.selfId}
            hostId={snapshot.hostId}
            connection={snapshot.connection}
            messages={snapshot.messages}
            transfers={snapshot.transfers}
            joinRequests={snapshot.joinRequests}
            profiles={snapshot.profiles}
            ownProfile={snapshot.ownProfile}
            onSend={handleSend}
            onSendFile={handleSendFile}
            onAdmit={handleAdmit}
            onReject={handleReject}
            onProfileSaved={handleProfileSaved}
            onLeave={handleLeave}
            onDeleted={handleDeleted}
        />
    )
}
