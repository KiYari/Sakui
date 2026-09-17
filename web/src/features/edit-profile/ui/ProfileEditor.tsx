import { useState, type FormEvent } from 'react'
import {
    PROFILE_COLOR_COUNT,
    PROFILE_NAME_MAX_LENGTH,
    saveOwnProfile,
    type Profile,
} from '../../../entities/session'

interface ProfileEditorProps {
    profile: Profile
    /** Receives the sanitised, already-saved profile. */
    onSaved: (profile: Profile) => void
    onCancel?: () => void
}

const COLORS = Array.from({ length: PROFILE_COLOR_COUNT }, (_, index) => index)

/**
 * Name and avatar colour, saved in this browser only. In a chat they are sent
 * end-to-end encrypted to the people in the room — the server never sees them.
 */
export function ProfileEditor({ profile, onSaved, onCancel }: ProfileEditorProps) {
    const [name, setName] = useState(profile.name)
    const [color, setColor] = useState(profile.color)

    const handleSubmit = (event: FormEvent) => {
        event.preventDefault()
        onSaved(saveOwnProfile({ name, color }))
    }

    return (
        <form className="profile-editor" onSubmit={handleSubmit}>
            <label className="profile-field">
                <span className="hint">Your name (optional)</span>
                <input
                    value={name}
                    maxLength={PROFILE_NAME_MAX_LENGTH}
                    placeholder="Shown to people in your chats"
                    onChange={(e) => setName(e.target.value)}
                />
            </label>
            <div className="swatches" role="radiogroup" aria-label="Avatar colour">
                {COLORS.map((index) => (
                    <button
                        key={index}
                        type="button"
                        role="radio"
                        aria-checked={color === index}
                        aria-label={`Colour ${index + 1}`}
                        className={`swatch avatar-color-${index} ${color === index ? 'selected' : ''}`}
                        onClick={() => setColor(index)}
                    />
                ))}
            </div>
            <div className="profile-actions">
                <button type="submit">Save</button>
                {onCancel && (
                    <button type="button" className="secondary" onClick={onCancel}>
                        Cancel
                    </button>
                )}
            </div>
        </form>
    )
}
