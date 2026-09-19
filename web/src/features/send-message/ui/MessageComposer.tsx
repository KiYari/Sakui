import { useState } from 'react'
import { draftFor, rememberDraft } from '../../../shared/lib/message-draft'

interface MessageComposerProps {
    chatId: string
    /** Only the session's `ready` state may encrypt, so the composer is gated on it. */
    enabled: boolean
    onSend: (text: string) => Promise<void>
}

export function MessageComposer({ chatId, enabled, onSend }: MessageComposerProps) {
    const [draft, setDraftState] = useState(() => draftFor(chatId))
    const [sendError, setSendError] = useState<string | null>(null)

    // Every keystroke persists, not just on unmount: a crashed tab or a closed
    // laptop lid leaves no chance to flush a draft that only lived in memory.
    const setDraft = (text: string) => {
        setDraftState(text)
        rememberDraft(chatId, text)
    }

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
