import { useState } from 'react'
import { createLink } from '../lib/api'

interface CreateLinkProps {
    onCreated: (chatId: string) => void
}

function CreateLink({ onCreated }: CreateLinkProps) {
    const [link, setLink] = useState<{ chatId: string; url: string } | null>(null)
    const [copied, setCopied] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [loading, setLoading] = useState(false)

    const handleCreate = async () => {
        setLoading(true)
        setError(null)
        try {
            const response = await createLink()
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
        <div className="screen">
            <h1>eeck</h1>
            <p className="subtitle">End-to-end encrypted chat. No accounts, no history.</p>

            {!link && (
                <button onClick={handleCreate} disabled={loading}>
                    {loading ? 'Creating link…' : 'Create a chat link'}
                </button>
            )}

            {error && <p className="error">{error}</p>}

            {link && (
                <div className="link-box">
                    <input readOnly value={link.url} onFocus={(e) => e.currentTarget.select()} />
                    <button onClick={handleCopy}>{copied ? 'Copied!' : 'Copy link'}</button>
                    <p className="hint">
                        Share this link with the person you want to chat with. Anyone with the link can join.
                    </p>
                    <button className="secondary" onClick={() => onCreated(link.chatId)}>
                        Enter chat
                    </button>
                </div>
            )}
        </div>
    )
}

export default CreateLink
