import { useState, type FormEvent } from 'react'
import {
    endpointFor,
    forgetServer,
    keyFor,
    parseServer,
    recentServers,
    DEFAULT_RELAY_PORT,
    SAME_ORIGIN_SERVER,
    type ServerMode,
} from '../../../shared/lib/server-target'
import { probeServer } from '../model/probeServer'
import './ServerPicker.css'

interface ServerPickerProps {
    mode: ServerMode
    /** Address to start with, e.g. the server a link named but could not be used as it was. */
    initialServer?: string
    /** Shown before anything is typed, e.g. why a link's own server was refused. */
    initialError?: string
    /** [key] is set only when the server asked for one. */
    onSelect: (server: string, key?: string) => void
}

/**
 * Asks which chat server to use — and, if that server wants one, its access
 * key — and checks both before moving on: a typo should fail here, not as a
 * chat that silently never connects. A same-origin build has no address to
 * ask for, so this shrinks to just the key.
 */
export function ServerPicker({ mode, initialServer, initialError, onSelect }: ServerPickerProps) {
    const sameOrigin = mode === 'same-origin'
    const [input, setInput] = useState(initialServer ?? '')
    const [key, setKey] = useState(() => keyFor(initialServer ?? SAME_ORIGIN_SERVER) ?? '')
    const [recent, setRecent] = useState(() => recentServers())
    const [error, setError] = useState<string | null>(initialError ?? null)
    const [checking, setChecking] = useState(false)

    const connect = async (rawAddress: string, rawKey: string) => {
        const parsed = sameOrigin ? { ok: true as const, server: SAME_ORIGIN_SERVER } : parseServer(rawAddress, mode)
        if (!parsed.ok) {
            setError(parsed.error)
            return
        }
        const accessKey = rawKey.trim() || undefined
        setChecking(true)
        setError(null)
        const result = await probeServer({ ...endpointFor(parsed.server, mode), key: accessKey })
        setChecking(false)
        if (result.status === 'ok') {
            // A key the server never asked for is not worth keeping.
            onSelect(parsed.server, result.keyRequired ? accessKey : undefined)
        } else {
            setError(result.error)
        }
    }

    const handleSubmit = (event: FormEvent) => {
        event.preventDefault()
        void connect(input, key)
    }

    return (
        <div className="server-picker">
            <form onSubmit={handleSubmit}>
                {!sameOrigin && (
                    <input
                        autoFocus
                        value={input}
                        onChange={(e) => setInput(e.target.value)}
                        placeholder={mode === 'relay' ? `203.0.113.7:${DEFAULT_RELAY_PORT}` : '203.0.113.7 or chat.example.com'}
                        aria-label="Server address"
                        autoCapitalize="none"
                        autoCorrect="off"
                        spellCheck={false}
                        disabled={checking}
                    />
                )}
                <input
                    type="password"
                    value={key}
                    onChange={(e) => setKey(e.target.value)}
                    placeholder={sameOrigin ? 'Access key' : 'Access key (if the server needs one)'}
                    aria-label="Access key"
                    autoComplete="off"
                    autoFocus={sameOrigin}
                    disabled={checking}
                />
                <button type="submit" disabled={checking || (!sameOrigin && input.trim() === '')}>
                    {checking ? 'Connecting…' : 'Connect'}
                </button>
            </form>
            {!sameOrigin && (
                <p className="hint">
                    {mode === 'relay'
                        ? `The server's public IP address. The port defaults to ${DEFAULT_RELAY_PORT}.`
                        : 'An IP address is reached over HTTPS through its sslip.io name, so the server must run with TLS (Caddy).'}
                </p>
            )}

            {error && <p className="error">{error}</p>}

            {!sameOrigin && recent.length > 0 && <p className="recent-servers-title">Recent servers</p>}
            {!sameOrigin && recent.length > 0 && (
                <ul className="recent-servers">
                    {recent.map((server) => (
                        <li key={server}>
                            <button
                                className="secondary"
                                disabled={checking}
                                onClick={() => {
                                    const saved = keyFor(server) ?? ''
                                    setInput(server)
                                    setKey(saved)
                                    void connect(server, saved)
                                }}
                            >
                                {server}
                            </button>
                            <button
                                className="secondary"
                                aria-label={`Forget ${server}`}
                                title="Forget"
                                disabled={checking}
                                onClick={() => {
                                    forgetServer(server)
                                    setRecent(recentServers())
                                }}
                            >
                                ×
                            </button>
                        </li>
                    ))}
                </ul>
            )}
        </div>
    )
}
