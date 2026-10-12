import { useState } from 'react'
import { createLink, type ServerEndpoint } from '../../../shared/api'
import { rememberOwnerToken } from '../../../shared/lib/owner-token'
import { CheckIcon, CopyIcon } from '../../../shared/ui/icons'
import './CreateLinkPanel.css'

interface CreateLinkPanelProps {
    endpoint: ServerEndpoint
    /** Goes into the shared link so whoever opens it connects to the same server. Omitted for a same-origin build. */
    server?: string
    onEnterChat: (chatId: string) => void
}

export function CreateLinkPanel({ endpoint, server, onEnterChat }: CreateLinkPanelProps) {
    const [link, setLink] = useState<{ chatId: string; url: string } | null>(null)
    const [copied, setCopied] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [loading, setLoading] = useState(false)

    const handleCreate = async () => {
        setLoading(true)
        setError(null)
        try {
            const response = await createLink(endpoint)
            // Only this browser gets to delete the chat; the shared URL never carries the token.
            rememberOwnerToken(response.hash, response.ownerToken)
            const url = new URL(window.location.href)
            if (server) url.searchParams.set('server', server)
            url.searchParams.set('chatId', response.hash)
            setLink({ chatId: response.hash, url: url.toString() })
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to create link.')
        } finally {
            setLoading(false)
        }
    }

    const handleCopy = async () => {
        if (!link) return
        await navigator.clipboard.writeText(link.url)
        setCopied(true)
        setTimeout(() => setCopied(false), 2000)
    }

    return (
        <div className="create-link">
            {!link && (
                <button onClick={() => void handleCreate()} disabled={loading}>
                    {loading ? 'Creating link…' : 'Create a chat link'}
                </button>
            )}

            {error && <p className="error">{error}</p>}

            {link && (
                <div className="link-box">
                    <div className="link-row">
                        <input readOnly value={link.url} onFocus={(e) => e.currentTarget.select()} aria-label="Chat link" />
                        <button onClick={() => void handleCopy()}>
                            {copied ? <CheckIcon /> : <CopyIcon />}
                            {copied ? 'Copied!' : 'Copy link'}
                        </button>
                    </div>
                    <p className="hint">
                        Share this link with the people you want to chat with. Whoever is in the chat first lets
                        everyone else in.
                    </p>
                    <button className="secondary" onClick={() => onEnterChat(link.chatId)}>
                        Enter chat
                    </button>
                </div>
            )}
        </div>
    )
}
