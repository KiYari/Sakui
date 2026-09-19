import { browserStorage, readJson, removeKey, writeJson, type KeyValueStorage } from '../local-store'

const PREFIX = 'eeck-draft:'

/**
 * The composer's unsent text, kept per chat so a reload or an accidental tab
 * close doesn't throw away something typed but not yet sent.
 */
export function rememberDraft(chatId: string, text: string, storage: KeyValueStorage | undefined = browserStorage()): void {
    if (!text) {
        forgetDraft(chatId, storage)
        return
    }
    writeJson(storage, PREFIX + chatId, text)
}

export function draftFor(chatId: string, storage: KeyValueStorage | undefined = browserStorage()): string {
    const stored = readJson(storage, PREFIX + chatId)
    return typeof stored === 'string' ? stored : ''
}

export function forgetDraft(chatId: string, storage: KeyValueStorage | undefined = browserStorage()): void {
    removeKey(storage, PREFIX + chatId)
}
