import { useState } from 'react'
import { createLink } from '../../../shared/api'
import { rememberOwnerToken } from '../../../shared/lib/owner-token'

interface CreateLinkPanelProps {
    onEnterChat: (chatId: string) => void
}

export function CreateLinkPanel({ onEnterChat }: CreateLinkPanelProps) {
    const [link, setLink] = useState<{ chatId: string; url: string } | null>(null)
    const [copied, setCopied] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [loading, setLoading] = useState(false)

    const handleCreate = async () => {
        setLoading(true)
        setError(null)
        try {
            const response = await createLink()
            // Only this browser gets to delete the chat; the shared URL never carries the token.
            rememberOwnerToken(response.hash, response.ownerToken)
            const url = new URL(window.location.href)
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
        <>
            {!link && (
                <button onClick={() => void handleCreate()} disabled={loading}>
                    {loading ? 'Creating link…' : 'Create a chat link'}
                </button>
            )}

            {error && <p className="error">{error}</p>}

            {link && (
                <div className="link-box">
                    <input readOnly value={link.url} onFocus={(e) => e.currentTarget.select()} />
                    <button onClick={() => void handleCopy()}>{copied ? 'Copied!' : 'Copy link'}</button>
                    <p className="hint">
                        Share this link with the people you want to chat with. Whoever is in the chat first lets
                        everyone else in.
                    </p>
                    <button className="secondary" onClick={() => onEnterChat(link.chatId)}>
                        Enter chat
                    </button>
                </div>
            )}
        </>
    )
}
