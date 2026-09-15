import { useEffect, useRef, useState } from 'react'
import { ChatSession, type ChatMessage } from '../lib/chatSession'
import { deleteLink } from '../lib/api'
import type { ConnectionStatus } from '../lib/e2ee-client'

interface ChatProps {
    chatId: string
    onLeave: () => void
}

function Chat({ chatId, onLeave }: ChatProps) {
    const [messages, setMessages] = useState<ChatMessage[]>([])
    const [peerOnline, setPeerOnline] = useState(false)
    const [keysExchanged, setKeysExchanged] = useState(false)
    const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>('connecting')
    const [draft, setDraft] = useState('')
    const [sendError, setSendError] = useState<string | null>(null)
    const sessionRef = useRef<ChatSession | null>(null)
    const userIdRef = useRef<string>(crypto.randomUUID())

    useEffect(() => {
        const session = new ChatSession(chatId, userIdRef.current, {
            onMessage: (message) => setMessages((prev) => [...prev, message]),
            onPeerOnlineChange: setPeerOnline,
            onKeysExchangedChange: setKeysExchanged,
            onConnectionStatusChange: setConnectionStatus,
        })
        sessionRef.current = session
        session.start().catch((err: unknown) => {
            console.error('Failed to start chat session', err)
        })
        return () => {
            session.dispose()
            sessionRef.current = null
        }
    }, [chatId])

    const handleSend = async () => {
        const text = draft.trim()
        if (!text || !sessionRef.current) return
        setSendError(null)
        try {
            await sessionRef.current.sendMessage(text)
            setDraft('')
        } catch (err) {
            setSendError(err instanceof Error ? err.message : 'Failed to send message.')
        }
    }

    const handleDelete = async () => {
        try {
            await deleteLink(chatId)
        } catch (err) {
            console.error('Failed to delete link', err)
        } finally {
            onLeave()
        }
    }

    return (
        <div className="screen chat-screen">
            <header className="chat-header">
                <div className="peer-status">
                    <span className={`status-dot ${peerOnline ? 'online' : 'offline'}`} />
                    {peerOnline ? 'Peer online' : 'Waiting for peer…'}
                </div>
                <div className={`keys-indicator ${keysExchanged ? 'exchanged' : ''}`}>
                    {keysExchanged ? 'Keys exchanged' : 'Exchanging keys…'}
                </div>
                <div className="connection-status">{connectionStatus}</div>
                <button className="danger" onClick={handleDelete}>
                    Delete link
                </button>
            </header>

            <div className="message-list">
                {messages.map((m) => (
                    <div key={m.id} className={`message ${m.sender}`}>
                        <span className="message-text">{m.text}</span>
                    </div>
                ))}
            </div>

            {sendError && <p className="error">{sendError}</p>}

            <div className="composer">
                <input
                    value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                    onKeyDown={(e) => {
                        if (e.key === 'Enter') void handleSend()
                    }}
                    placeholder={keysExchanged ? 'Type a message…' : 'Waiting for keys to be exchanged…'}
                    disabled={!keysExchanged}
                />
                <button onClick={() => void handleSend()} disabled={!keysExchanged || !draft.trim()}>
                    Send
                </button>
            </div>
        </div>
    )
}

export default Chat
