import { useRef, useState } from 'react'
import { formatBytes, MAX_FILE_BYTES } from '../../../entities/session'

interface FileAttachButtonProps {
    enabled: boolean
    onSend: (file: File) => Promise<void>
}

export function FileAttachButton({ enabled, onSend }: FileAttachButtonProps) {
    const inputRef = useRef<HTMLInputElement>(null)
    const [error, setError] = useState<string | null>(null)
    const [busy, setBusy] = useState(false)

    const handlePick = async (file: File | undefined) => {
        if (!file) return
        setError(null)
        setBusy(true)
        try {
            await onSend(file)
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to send file.')
        } finally {
            setBusy(false)
            // Clear the input, or picking the same file twice in a row fires no change event.
            if (inputRef.current) inputRef.current.value = ''
        }
    }

    return (
        <>
            {error && <p className="error">{error}</p>}
            <input
                ref={inputRef}
                type="file"
                hidden
                onChange={(e) => void handlePick(e.target.files?.[0])}
            />
            <button
                className="secondary"
                title={`Attach a file (up to ${formatBytes(MAX_FILE_BYTES)})`}
                disabled={!enabled || busy}
                onClick={() => inputRef.current?.click()}
            >
                {busy ? 'Sending…' : 'Attach'}
            </button>
        </>
    )
}
