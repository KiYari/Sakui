import { useEffect } from 'react'
import { reportAccessDenied, type ServerEndpoint } from '../../../shared/api'
import { probeServer } from './probeServer'

const FIRST_CHECK_MS = 1500
const RECHECK_MS = 5000

/**
 * While a connection is struggling, find out whether the cause is the access
 * key. A socket refused at the handshake looks to a browser like any other
 * failed connection (just a close with no reason), so a rotated key would
 * otherwise read as endless "reconnecting". The server decides — this only asks
 * it — and a rejected key is reported so the app can ask for the new one.
 */
export function useAccessWatch(endpoint: ServerEndpoint, struggling: boolean): void {
    useEffect(() => {
        if (!struggling) return
        let cancelled = false
        let timer: ReturnType<typeof setTimeout> | undefined

        const check = async () => {
            const result = await probeServer(endpoint)
            if (cancelled) return
            if (result.status === 'key-needed') {
                reportAccessDenied(result.error)
                return
            }
            timer = setTimeout(() => void check(), RECHECK_MS)
        }
        timer = setTimeout(() => void check(), FIRST_CHECK_MS)

        return () => {
            cancelled = true
            if (timer) clearTimeout(timer)
        }
    }, [endpoint, struggling])
}
