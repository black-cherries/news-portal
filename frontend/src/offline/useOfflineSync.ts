import { useEffect } from 'react'
import { syncDownloads } from './downloads'

const MIN_INTERVAL_MS = 10 * 60 * 1000

/**
 * Applies publisher restrictions to downloads at start-up and whenever the
 * device comes back online (FR-063). At most every 10 minutes.
 */
export function useOfflineSync() {
  useEffect(() => {
    let last = 0
    const run = () => {
      if (!navigator.onLine || Date.now() - last < MIN_INTERVAL_MS) return
      last = Date.now()
      syncDownloads().catch(() => {
        // Offline or server unreachable: the next reconnect tries again.
        last = 0
      })
    }
    run()
    window.addEventListener('online', run)
    return () => window.removeEventListener('online', run)
  }, [])
}
