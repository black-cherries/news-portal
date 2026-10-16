import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api } from '../api/client'
import type { Me, Preferences } from '../api/types'
import { useI18n } from '../i18n/I18nProvider'
import { isLang } from '../i18n/messages'

interface Auth {
  /** undefined while the first check is running; null when signed out. */
  me: Me | null | undefined
  isReader: boolean
  /** Call after a successful sign-in response. */
  signedIn: (me: Me) => Promise<void>
  signOut: () => Promise<void>
  /** Forget the session locally (e.g. after account deletion). */
  clear: () => void
}

const AuthContext = createContext<Auth | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null | undefined>(undefined)
  const { setLang } = useI18n()

  // A signed-in reader's interface language follows their saved preference.
  const applyPreferences = useCallback(
    async (user: Me) => {
      if (user.accountType !== 'READER') return
      try {
        const prefs = await api.get<Preferences>('/me/preferences')
        if (isLang(prefs.uiLanguage)) setLang(prefs.uiLanguage)
      } catch {
        // Keep the current language.
      }
    },
    [setLang],
  )

  useEffect(() => {
    let cancelled = false
    api
      .get<Me>('/auth/me')
      .then(async (user) => {
        if (cancelled) return
        setMe(user)
        await applyPreferences(user)
      })
      .catch(() => {
        // 401 means signed out; any other failure is treated the same way.
        if (!cancelled) setMe(null)
      })
    return () => {
      cancelled = true
    }
  }, [applyPreferences])

  const value = useMemo<Auth>(
    () => ({
      me,
      isReader: me?.accountType === 'READER',
      signedIn: async (user: Me) => {
        setMe(user)
        await applyPreferences(user)
      },
      signOut: async () => {
        try {
          await api.post('/auth/logout')
        } finally {
          setMe(null)
        }
      },
      clear: () => setMe(null),
    }),
    [me, applyPreferences],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): Auth {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
