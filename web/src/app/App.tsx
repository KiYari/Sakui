import { useEffect, useState } from 'react'
import { ChatPage } from '../pages/chat'
import { HomePage } from '../pages/home'

function getChatIdFromUrl(): string | null {
    return new URLSearchParams(window.location.search).get('chatId')
}

export function App() {
    const [chatId, setChatId] = useState<string | null>(() => getChatIdFromUrl())

    useEffect(() => {
        const onPopState = () => setChatId(getChatIdFromUrl())
        window.addEventListener('popstate', onPopState)
        return () => window.removeEventListener('popstate', onPopState)
    }, [])

    const enterChat = (id: string) => {
        const url = new URL(window.location.href)
        url.searchParams.set('chatId', id)
        window.history.pushState({}, '', url)
        setChatId(id)
    }

    const leaveChat = () => {
        const url = new URL(window.location.href)
        url.searchParams.delete('chatId')
        window.history.pushState({}, '', url)
        setChatId(null)
    }

    if (chatId) {
        return <ChatPage chatId={chatId} onLeave={leaveChat} />
    }
    return <HomePage onEnterChat={enterChat} />
}
