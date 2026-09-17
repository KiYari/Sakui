import { useState } from 'react'
import { loadOwnProfile, OwnAvatar } from '../../../entities/session'
import { CreateLinkPanel } from '../../../features/create-link'
import { ProfileEditor } from '../../../features/edit-profile'

interface HomePageProps {
    onEnterChat: (chatId: string) => void
}

export function HomePage({ onEnterChat }: HomePageProps) {
    const [profile, setProfile] = useState(loadOwnProfile)
    const [saved, setSaved] = useState(false)

    return (
        <div className="screen">
            <h1>eeck</h1>
            <p className="subtitle">End-to-end encrypted chat. No accounts, no history.</p>
            <CreateLinkPanel onEnterChat={onEnterChat} />

            <section className="home-profile">
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
