import { describe, expect, it } from 'vitest'
import { memoryStorage } from '../../local-store'
import { applyTheme, loadTheme, nextTheme, parseTheme, saveTheme } from '../theme'

describe('theme preference', () => {
    it('treats anything unknown as system', () => {
        expect(parseTheme(undefined)).toBe('system')
        expect(parseTheme('purple')).toBe('system')
        expect(parseTheme('dark')).toBe('dark')
    })

    it('round-trips through storage', () => {
        const storage = memoryStorage()
        expect(loadTheme(storage)).toBe('system')
        saveTheme('light', storage)
        expect(loadTheme(storage)).toBe('light')
    })

    it('cycles system → light → dark → system', () => {
        expect(nextTheme('system')).toBe('light')
        expect(nextTheme('light')).toBe('dark')
        expect(nextTheme('dark')).toBe('system')
    })

    it('sets the attribute only for an explicit choice', () => {
        // The suite runs without a DOM; the function only touches these two methods.
        const attrs = new Map<string, string>()
        const root = {
            setAttribute: (k: string, v: string) => void attrs.set(k, v),
            removeAttribute: (k: string) => void attrs.delete(k),
        } as unknown as HTMLElement
        applyTheme('dark', root)
        expect(attrs.get('data-theme')).toBe('dark')
        applyTheme('system', root)
        expect(attrs.has('data-theme')).toBe(false)
    })
})
