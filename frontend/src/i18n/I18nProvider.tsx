import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { RTL_LANGUAGES, isLang, messages, type Lang, type MessageKey } from './messages'

const STORAGE_KEY = 'np.uiLanguage'

const LOCALES: Record<Lang, string> = { en: 'en-IN', hi: 'hi-IN', ur: 'ur-PK', ar: 'ar' }

interface I18n {
  lang: Lang
  dir: 'ltr' | 'rtl'
  setLang: (lang: Lang) => void
  t: (key: MessageKey, vars?: Record<string, string | number>) => string
  formatDateTime: (iso: string) => string
  /** Storage sizes such as "1.2 MB", in the interface language. */
  formatBytes: (bytes: number) => string
  /** "12 minutes ago"; falls back to the full date after a week. */
  formatRelative: (iso: string) => string
  categoryName: (code: string) => string
}

const I18nContext = createContext<I18n | null>(null)

function initialLang(): Lang {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (isLang(stored)) return stored
  } catch {
    // Storage can be unavailable (private mode); fall back below.
  }
  // English until the reader chooses another language (owner decision, 2026-10-16).
  return 'en'
}

export function I18nProvider({ children, initial }: { children: ReactNode; initial?: Lang }) {
  const [lang, setLangState] = useState<Lang>(() => initial ?? initialLang())
  const dir = RTL_LANGUAGES.has(lang) ? 'rtl' : 'ltr'

  // The whole page follows the interface language; article text sets its own lang/dir.
  useEffect(() => {
    document.documentElement.lang = lang
    document.documentElement.dir = dir
  }, [lang, dir])

  const setLang = useCallback((next: Lang) => {
    setLangState(next)
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // Not critical.
    }
  }, [])

  const value = useMemo<I18n>(() => {
    const dict = messages[lang]
    const t = (key: MessageKey, vars?: Record<string, string | number>) => {
      let text = dict[key] ?? messages.en[key] ?? key
      if (vars) {
        for (const [name, v] of Object.entries(vars)) text = text.replaceAll(`{${name}}`, String(v))
      }
      return text
    }
    const formatter = new Intl.DateTimeFormat(LOCALES[lang], { dateStyle: 'medium', timeStyle: 'short' })
    return {
      lang,
      dir,
      setLang,
      t,
      formatDateTime: (iso: string) => formatter.format(new Date(iso)),
      formatRelative: (iso: string) => {
        const seconds = (Date.parse(iso) - Date.now()) / 1000
        const abs = Math.abs(seconds)
        if (abs >= 7 * 86400) return formatter.format(new Date(iso))
        const rtf = new Intl.RelativeTimeFormat(LOCALES[lang], { numeric: 'auto', style: 'short' })
        if (abs < 3600) return rtf.format(Math.round(seconds / 60), 'minute')
        if (abs < 86400) return rtf.format(Math.round(seconds / 3600), 'hour')
        return rtf.format(Math.round(seconds / 86400), 'day')
      },
      formatBytes: (bytes: number) => {
        const [unit, n] = bytes >= 1e6 ? ['megabyte', bytes / 1e6] : ['kilobyte', Math.max(bytes, 1) / 1e3]
        return new Intl.NumberFormat(LOCALES[lang], { style: 'unit', unit, maximumFractionDigits: 1 }).format(n)
      },
      categoryName: (code: string) => {
        const key = `category.${code}` as MessageKey
        return key in dict ? t(key) : code
      },
    }
  }, [lang, dir, setLang])

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>
}

export function useI18n(): I18n {
  const ctx = useContext(I18nContext)
  if (!ctx) throw new Error('useI18n must be used inside I18nProvider')
  return ctx
}
