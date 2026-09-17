import { useState } from 'react'

interface MessageComposerProps {
    /** Only the session's `ready` state may encrypt, so the composer is gated on it. */
    enabled: boolean
    onSend: (text: string) => Promise<void>
}

export function MessageComposer({ enabled, onSend }: MessageComposerProps) {
    const [draft, setDraft] = useState('')
    const [sendError, setSendError] = useState<string | null>(null)

    const handleSend = async () => {
        const text = draft.trim()
        if (!text) return
        setSendError(null)
        try {
            await onSend(text)
            setDraft('')
        } catch (err) {
            setSendError(err instanceof Error ? err.message : 'Failed to send message.')
        }
    }

    // The `.composer` container belongs to the widget, which lays this out next
    // to the attach button; wrapping again here would nest the flex row.
    return (
        <>
            {sendError && <p className="error">{sendError}</p>}
            <input
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                onKeyDown={(e) => {
                    if (e.key === 'Enter') void handleSend()
                }}
                placeholder={enabled ? 'Type a message…' : 'Waiting for keys to be exchanged…'}
                disabled={!enabled}
            />
            <button onClick={() => void handleSend()} disabled={!enabled || !draft.trim()}>
                Send
            </button>
        </>
    )
}
