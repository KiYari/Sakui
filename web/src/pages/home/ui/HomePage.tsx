import { useState } from 'react'
import type { ServerEndpoint } from '../../../shared/api'
import { loadOwnProfile, OwnAvatar } from '../../../entities/session'
import { CreateLinkPanel } from '../../../features/create-link'
import { ProfileEditor } from '../../../features/edit-profile'
import { ThemeToggle } from '../../../features/toggle-theme'
import { Logo } from '../../../shared/ui/logo'
import './HomePage.css'

interface HomePageProps {
    endpoint: ServerEndpoint
    /** The server in use, or undefined in a same-origin build, which has just the one. */
    server?: string
    onChangeServer?: () => void
    onEnterChat: (chatId: string) => void
}

export function HomePage({ endpoint, server, onChangeServer, onEnterChat }: HomePageProps) {
    const [profile, setProfile] = useState(loadOwnProfile)
    const [saved, setSaved] = useState(false)

    return (
        <div className="screen">
            <div className="screen-top">
                <div className="brand">
                    <Logo />
                    <h1>eeck</h1>
                </div>
                <ThemeToggle />
            </div>
            <p className="subtitle">End-to-end encrypted chat. No accounts, no history.</p>
            {server && (
                <p className="server-line">
                    Server: <code>{server}</code>{' '}
                    {onChangeServer && (
                        <button className="secondary" onClick={onChangeServer}>
                            Change
                        </button>
                    )}
                </p>
            )}
            <div className="card">
                <CreateLinkPanel endpoint={endpoint} server={server} onEnterChat={onEnterChat} />
            </div>

            <section className="home-profile card">
                <h2>
                    <OwnAvatar profile={profile} /> Your profile
                </h2>
                <p className="hint">Stored only in this browser, and sent encrypted to the people you chat with.</p>
                <ProfileEditor
                    profile={profile}
                    onSaved={(next) => {
                        setProfile(next)
                        setSaved(true)
                        setTimeout(() => setSaved(false), 2000)
                    }}
                />
                {saved && <p className="hint">Saved.</p>}
            </section>
        </div>
    )
}
