import { useCallback, useEffect, useState } from 'react'
import { ApiError, api } from '../api/client'
import { useAuth } from '../auth/AuthProvider'
import type { StaffRole } from './types'

/** GETs an API path and offers a reload; errors are kept for display. Pass null to load nothing. */
export function useLoad<T>(path: string | null) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<unknown>(null)

  const reload = useCallback(async () => {
    if (path === null) return
    try {
      setData(await api.get<T>(path))
      setError(null)
    } catch (err) {
      setError(err)
    }
  }, [path])

  useEffect(() => {
    void reload()
  }, [reload])

  return { data, error, reload }
}

export function useRoles() {
  const { me } = useAuth()
  const roles = new Set<string>(me?.roles ?? [])
  return { has: (role: StaffRole) => roles.has(role), roles }
}

/** Staff-facing error text: the server's problem detail is written for staff. */
export function errorText(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) return 'Your role does not allow this.'
    if (err.status === 429) return 'Too many attempts. Wait a moment and try again.'
    return typeof err.body.detail === 'string' ? err.body.detail : `Request failed (HTTP ${err.status}).`
  }
  return 'Something went wrong. Check that the backend is running.'
}

/** datetime-local value (local time) → ISO instant, or null. */
export function localToIso(value: FormDataEntryValue | null): string | null {
  const s = value ? String(value) : ''
  return s ? new Date(s).toISOString() : null
}
