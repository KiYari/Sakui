const STORAGE_KEY = 'eeck-tab-scope'

let fallbackScope: string | undefined

/**
 * A stable id for this browser tab: survives reloads, differs between tabs.
 *
 * Identity keys are scoped by it. Keyed per chat alone, every tab of the same
 * browser would share one key, and since an id is now a proven key, two tabs
 * would be one participant — each evicting the other on connect. sessionStorage
 * gives exactly the lifetime wanted: one per tab, kept across a refresh.
 */
export function getTabScope(): string {
    try {
        const existing = sessionStorage.getItem(STORAGE_KEY)
        if (existing) return existing
        const created = crypto.randomUUID()
        sessionStorage.setItem(STORAGE_KEY, created)
        return created
    } catch {
        // Storage can be unavailable (privacy modes, sandboxed frames); a per-load
        // scope still keeps tabs apart, it just won't survive a reload.
        fallbackScope ??= crypto.randomUUID()
        return fallbackScope
    }
}
