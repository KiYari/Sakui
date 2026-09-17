import { useState } from 'react'
import { deleteLink } from '../../../shared/api'
import { forgetOwnerToken, ownerTokenFor } from '../../../shared/lib/owner-token'

interface DeleteLinkButtonProps {
    chatId: string
    onDeleted: () => void
}

/**
 * Rendered only in the browser that created the link — the one holding its
 * owner token. Everyone else would just get a 403, so they aren't offered it.
 */
export function DeleteLinkButton({ chatId, onDeleted }: DeleteLinkButtonProps) {
    const [token] = useState(() => ownerTokenFor(chatId))
    const [error, setError] = useState<string | null>(null)

    if (!token) return null

    const handleDelete = async () => {
        try {
            await deleteLink(chatId, token)
            forgetOwnerToken(chatId)
            onDeleted()
        } catch (err) {
            // Unlike leaving, deleting is a claim about everyone's chat: if it didn't
            // happen, say so instead of pretending it did.
            setError(err instanceof Error ? err.message : 'Failed to delete the chat.')
        }
    }

    return (
        <>
            <button className="danger" onClick={() => void handleDelete()}>
                Delete chat
            </button>
            {error && <span className="error">{error}</span>}
        </>
    )
}
