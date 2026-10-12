import { useEffect, useMemo, useState } from 'react'
import { onAccessDenied, type ServerEndpoint } from '../shared/api'
import { probeServer } from '../features/select-server'
import {
    endpointFor,
    forgetKey,
    keyFor,
    parseServer,
    rememberKey,
    rememberServer,
    SAME_ORIGIN_SERVER,
    SERVER_MODE,
} from '../shared/lib/server-target'
import { ChatPage } from '../pages/chat'
import { ConnectPage } from '../pages/connect'
import { HomePage } from '../pages/home'

function getChatIdFromUrl(): string | null {
    return new URLSearchParams(window.location.search).get('chatId')
}

/**
 * The server named by the URL's `?server=` — a shared chat link carries it, so
 * whoever opens the link lands on the server the chat actually lives on. A value
 * that does not parse is reported rather than silently ignored. A same-origin
 * build always has exactly one server.
 */
function getServerFromUrl(): { server: string | null; error?: string } {
    if (SERVER_MODE === 'same-origin') return { server: SAME_ORIGIN_SERVER }
    const raw = new URLSearchParams(window.location.search).get('server')
    if (raw === null) return { server: null }
    const parsed = parseServer(raw, SERVER_MODE)
    return parsed.ok ? { server: parsed.server } : { server: null, error: `The link names a server that cannot be used. ${parsed.error}` }
}

/**
 * Where the user is before the chat UI: choosing a server (and its key), having
 * a named server checked, or done. Nothing can be sent without a server, so
 * `ready` is only reached once one answered and accepted our key.
 */
type Stage =
    | { name: 'choose'; prefill?: string; error?: string }
    | { name: 'checking' }
    | { name: 'ready' }

interface Target {
    server: string | null
    key: string | undefined
    stage: Stage
}

function initialTarget(): Target {
    const { server, error } = getServerFromUrl()
    if (server === null) return { server, key: undefined, stage: { name: 'choose', error } }
    return { server, key: keyFor(server), stage: { name: 'checking' } }
}

export function App() {
    const [chatId, setChatId] = useState<string | null>(() => getChatIdFromUrl())
    const [target, setTarget] = useState(initialTarget)
    const { server, key, stage } = target

    useEffect(() => {
        const onPopState = () => {
            setChatId(getChatIdFromUrl())
            const next = getServerFromUrl()
            setTarget((current) =>
                next.server === current.server
                    ? current
                    : next.server === null
                      ? { server: null, key: undefined, stage: { name: 'choose', error: next.error } }
                      : { server: next.server, key: keyFor(next.server), stage: { name: 'checking' } },
            )
        }
        window.addEventListener('popstate', onPopState)
        return () => window.removeEventListener('popstate', onPopState)
    }, [])

    const endpoint: ServerEndpoint = useMemo(
        () => (server === null ? { base: '' } : { ...endpointFor(server, SERVER_MODE), key }),
        [server, key],
    )

    // The server stopped accepting our key (changed, then restarted), or never accepted
    // the one in this link. Whatever noticed it, the way out is the same: ask again,
    // keeping the chat in the URL so the new key lands the user back in it.
    useEffect(
        () =>
            onAccessDenied((message) => {
                setTarget((current) => {
                    if (current.server === null || current.stage.name === 'choose') return current
                    forgetKey(current.server)
                    return {
                        server: null,
                        key: undefined,
                        stage: {
                            name: 'choose',
                            prefill: current.server === SAME_ORIGIN_SERVER ? undefined : current.server,
                            error: `${message} Enter the server's current access key.`,
                        },
                    }
                })
            }),
        [],
    )

    // A link or a reload names a server without having been through the picker,
    // so check it (and the key we remembered for it) before showing anything.
    useEffect(() => {
        if (stage.name !== 'checking' || server === null) return
        let cancelled = false
        void probeServer(endpoint).then((result) => {
            if (cancelled) return
            if (result.status === 'ok') {
                setTarget((t) => ({ ...t, stage: { name: 'ready' } }))
            } else if (result.status === 'unreachable' && SERVER_MODE === 'same-origin') {
                // Nothing to choose instead; behave as before and let the chat report it.
                setTarget((t) => ({ ...t, stage: { name: 'ready' } }))
            } else {
                setTarget({ server: null, key: undefined, stage: { name: 'choose', prefill: server, error: result.error } })
            }
        })
        return () => {
            cancelled = true
        }
    }, [stage.name, server, endpoint])

    const enterChat = (id: string) => {
        const url = new URL(window.location.href)
        url.searchParams.set('chatId', id)
        window.history.pushState({}, '', url)
        setChatId(id)
    }

    const leaveChat = () => {
        const url = new URL(window.location.href)
        url.searchParams.delete('chatId')
        window.history.pushState({}, '', url)
        setChatId(null)
    }

    const selectServer = (next: string, nextKey?: string) => {
        if (SERVER_MODE !== 'same-origin') {
            rememberServer(next)
            const url = new URL(window.location.href)
            url.searchParams.set('server', next)
            window.history.pushState({}, '', url)
        }
        if (nextKey) rememberKey(next, nextKey)
        else forgetKey(next)
        setTarget({ server: next, key: nextKey, stage: { name: 'ready' } })
    }

    // Drops the chat too: an id only means something on the server that issued it.
    const changeServer = () => {
        const url = new URL(window.location.href)
        url.searchParams.delete('server')
        url.searchParams.delete('chatId')
        window.history.pushState({}, '', url)
        setChatId(null)
        setTarget({ server: null, key: undefined, stage: { name: 'choose' } })
    }

    if (stage.name === 'choose') {
        return (
            <ConnectPage
                mode={SERVER_MODE}
                joiningChat={chatId !== null}
                initialServer={stage.prefill}
                initialError={stage.error}
                onSelect={selectServer}
            />
        )
    }
    if (stage.name === 'checking') {
        return (
            <div className="screen" aria-busy="true">
                <p className="subtitle">Connecting…</p>
            </div>
        )
    }

    if (chatId) {
        return <ChatPage endpoint={endpoint} chatId={chatId} onLeave={leaveChat} />
    }
    return (
        <HomePage
            endpoint={endpoint}
            server={SERVER_MODE === 'same-origin' ? undefined : (server ?? undefined)}
            onChangeServer={SERVER_MODE === 'same-origin' ? undefined : changeServer}
            onEnterChat={enterChat}
        />
    )
}
