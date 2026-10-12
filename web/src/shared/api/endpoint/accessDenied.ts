/**
 * A server that stops accepting this browser's access key (it was changed and the
 * server restarted) can say so in many places: a REST call, or a socket that
 * never opens. Each of them reports here, and the app reacts once — back to the
 * key prompt — instead of every caller growing its own handling.
 */
type AccessDeniedHandler = (message: string) => void

const handlers = new Set<AccessDeniedHandler>()

export function onAccessDenied(handler: AccessDeniedHandler): () => void {
    handlers.add(handler)
    return () => {
        handlers.delete(handler)
    }
}

export function reportAccessDenied(message: string): void {
    handlers.forEach((handler) => handler(message))
}
