import { ServerPicker } from '../../../features/select-server'
import { ThemeToggle } from '../../../features/toggle-theme'
import type { ServerMode } from '../../../shared/lib/server-target'
import { Logo } from '../../../shared/ui/logo'

interface ConnectPageProps {
    mode: ServerMode
    /** True when the user arrived through a chat link, which they enter right after picking. */
    joiningChat: boolean
    initialServer?: string
    initialError?: string
    onSelect: (server: string, key?: string) => void
}

function subtitleFor(mode: ServerMode, joiningChat: boolean): string {
    // A same-origin build only lands here when its one server asks for a key.
    if (mode === 'same-origin') return 'This server is protected. Enter its password or a one-time code to continue.'
    return joiningChat ? 'Which server is this chat on?' : 'Which server do you want to connect to?'
}

export function ConnectPage({ mode, joiningChat, initialServer, initialError, onSelect }: ConnectPageProps) {
    return (
        <div className="screen">
            <div className="screen-top">
                <div className="brand">
                    <Logo />
                    <h1>eeck</h1>
                </div>
                <ThemeToggle />
            </div>
            <p className="subtitle">{subtitleFor(mode, joiningChat)}</p>
            <div className="card">
                <ServerPicker mode={mode} initialServer={initialServer} initialError={initialError} onSelect={onSelect} />
            </div>
        </div>
    )
}
