import { useEffect, useState } from 'react'
import CreateLink from './screens/CreateLink'
import Chat from './screens/Chat'

function getChatIdFromUrl(): string | null {
    return new URLSearchParams(window.location.search).get('chatId')
}

function App() {
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
        return <Chat chatId={chatId} onLeave={leaveChat} />
    }
    return <CreateLink onCreated={enterChat} />
}

export default App
