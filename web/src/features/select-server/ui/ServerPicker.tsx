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
import { signInToServer } from '../model/probeServer'
import './ServerPicker.css'

interface ServerPickerProps {
    mode: ServerMode
    /** Address to start with, e.g. the server a link named but could not be used as it was. */
    initialServer?: string
    /** Shown before anything is typed, e.g. why a link's own server was refused. */
    initialError?: string
    /** [token] is the session this sign-in opened; absent when the server asked for nothing. */
    onSelect: (server: string, token?: string) => void
}

/**
 * Asks which chat server to use — and, if that server is protected, the password
 * or one-time code — and checks both before moving on: a typo should fail here, not as a
 * chat that silently never connects. A same-origin build has no address to
 * ask for, so this shrinks to just the password or code.
 */
export function ServerPicker({ mode, initialServer, initialError, onSelect }: ServerPickerProps) {
    const sameOrigin = mode === 'same-origin'
    const [input, setInput] = useState(initialServer ?? '')
    // What is typed is a password or a one-time code and is never remembered; only the session token it buys is.
    const [secret, setSecret] = useState('')
    const [recent, setRecent] = useState(() => recentServers())
    const [error, setError] = useState<string | null>(initialError ?? null)
    const [checking, setChecking] = useState(false)

    const connect = async (rawAddress: string, rawSecret: string) => {
        const parsed = sameOrigin ? { ok: true as const, server: SAME_ORIGIN_SERVER } : parseServer(rawAddress, mode)
        if (!parsed.ok) {
            setError(parsed.error)
            return
        }
        setChecking(true)
        setError(null)
        const result = await signInToServer(endpointFor(parsed.server, mode), {
            secret: rawSecret,
            savedToken: keyFor(parsed.server),
        })
        setChecking(false)
        if (result.status === 'ok') {
            setSecret('')
            onSelect(parsed.server, result.token)
        } else {
            setError(result.error)
        }
    }

    const handleSubmit = (event: FormEvent) => {
        event.preventDefault()
        void connect(input, secret)
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
                    value={secret}
                    onChange={(e) => setSecret(e.target.value)}
                    placeholder={sameOrigin ? 'Password or one-time code' : 'Password or one-time code (if the server needs one)'}
                    aria-label="Password or one-time code"
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
                                    setInput(server)
                                    void connect(server, secret)
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
