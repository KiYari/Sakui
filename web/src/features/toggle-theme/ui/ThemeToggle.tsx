import { useState } from 'react'
import { applyTheme, loadTheme, nextTheme, saveTheme, type ThemePreference } from '../../../shared/lib/theme'
import { MonitorIcon, MoonIcon, SunIcon } from '../../../shared/ui/icons'
import './ThemeToggle.css'

const LABELS: Record<ThemePreference, string> = {
    system: 'Theme: follow system',
    light: 'Theme: light',
    dark: 'Theme: dark',
}

/** One button that steps system → light → dark; the choice is remembered in this browser. */
export function ThemeToggle() {
    const [theme, setTheme] = useState(loadTheme)

    const handleClick = () => {
        const next = nextTheme(theme)
        applyTheme(next)
        saveTheme(next)
        setTheme(next)
    }

    const Icon = theme === 'light' ? SunIcon : theme === 'dark' ? MoonIcon : MonitorIcon
    return (
        <button className="secondary icon" onClick={handleClick} title={LABELS[theme]} aria-label={LABELS[theme]}>
            {/* Keyed so each step replays the swap animation. */}
            <span key={theme} className="theme-toggle-icon">
                <Icon />
            </span>
        </button>
    )
}
