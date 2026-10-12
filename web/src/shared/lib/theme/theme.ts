import { browserStorage, readJson, writeJson, type KeyValueStorage } from '../local-store'

/**
 * `system` follows the OS and sets no attribute, so the stylesheet's
 * `prefers-color-scheme` rule decides; `light` and `dark` are explicit choices
 * written to `<html data-theme>` (see `app/styles/tokens.css`).
 */
export type ThemePreference = 'system' | 'light' | 'dark'

const THEME_KEY = 'eeck-theme'

/** The order the toggle steps through. */
export const THEME_CYCLE: readonly ThemePreference[] = ['system', 'light', 'dark']

export function parseTheme(raw: unknown): ThemePreference {
    return raw === 'light' || raw === 'dark' ? raw : 'system'
}

export function loadTheme(storage: KeyValueStorage | undefined = browserStorage()): ThemePreference {
    return parseTheme(readJson(storage, THEME_KEY))
}

export function saveTheme(theme: ThemePreference, storage: KeyValueStorage | undefined = browserStorage()): void {
    writeJson(storage, THEME_KEY, theme)
}

export function nextTheme(current: ThemePreference): ThemePreference {
    return THEME_CYCLE[(THEME_CYCLE.indexOf(current) + 1) % THEME_CYCLE.length]
}

export function applyTheme(theme: ThemePreference, root: HTMLElement = document.documentElement): void {
    if (theme === 'system') root.removeAttribute('data-theme')
    else root.setAttribute('data-theme', theme)
}
