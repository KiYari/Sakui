import { useEffect, useMemo, useSyncExternalStore } from 'react'
import { deleteKeyPair, deleteKeyPairsForChat, getOrCreateKeyPair } from '../../../shared/lib/key-store'
import { forgetOwnerToken } from '../../../shared/lib/owner-token'
import { getTabScope } from '../../../shared/lib/tab-scope'
import { createAdmissionMemory, forgetAdmissionMemory } from '../model/admissionMemory'
import { ChatSession, type SessionSnapshot } from '../model/ChatSession'
import { loadOwnProfile } from '../model/profile'

/**
 * Binds a [ChatSession] to React, wiring in everything this browser keeps
 * about the chat.
 *
 * `useSyncExternalStore` is the right tool here because the session lives
 * outside React entirely — a WebSocket pushes into it on its own schedule.
 * Subscribing directly (rather than mirroring every event into `useState`)
 * removes a whole class of tearing and double-subscribe problems under
 * StrictMode.
 *
 * The key is stored per chat *and* per tab: an id is a proven key, so two
 * tabs sharing one would be the same participant and evict each other.
 */
export function useChatSession(chatId: string): {
    session: ChatSession
    snapshot: SessionSnapshot
} {
    const session = useMemo(() => {
        const scopeKey = `${chatId}:${getTabScope()}`
        return new ChatSession(chatId, {
            loadKeyPair: () => getOrCreateKeyPair(scopeKey),
            profile: loadOwnProfile(),
            admissionMemory: createAdmissionMemory(chatId),
            forgetOwnKey: () => deleteKeyPair(scopeKey),
            forgetChat: async () => {
                forgetOwnerToken(chatId)
                forgetAdmissionMemory(chatId)
                await deleteKeyPairsForChat(chatId)
            },
        })
    }, [chatId])

    useEffect(() => {
        session.start().catch((error: unknown) => {
            console.error('Failed to start chat session', error)
        })
        return () => {
            session.dispose()
        }
    }, [session])

    const snapshot = useSyncExternalStore(session.subscribe, session.getSnapshot)

    return { session, snapshot }
}
